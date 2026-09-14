package app.morphe.extension.shared;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Holds every shared worker and queue slot until a rejection path has been exercised. */
public final class BackgroundPoolSaturation implements AutoCloseable {
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Future<?>> held = new ArrayList<>();
    private boolean released;

    private BackgroundPoolSaturation() {
    }

    public static BackgroundPoolSaturation fill() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Field field = Utils.class.getDeclaredField("backgroundThreadPool");
        field.setAccessible(true);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(null);
        BackgroundPoolSaturation saturation = new BackgroundPoolSaturation();
        CountDownLatch entered = new CountDownLatch(pool.getMaximumPoolSize());
        int jobs = pool.getMaximumPoolSize() + pool.getQueue().remainingCapacity();
        for (int index = 0; index < jobs; index++) {
            saturation.held.add(Utils.submitOnBackgroundThread(() -> {
                entered.countDown();
                if (!saturation.release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release a held worker");
                }
                return null;
            }));
        }
        if (!entered.await(2, TimeUnit.SECONDS) || pool.getQueue().remainingCapacity() != 0) {
            saturation.release();
            throw new AssertionError("shared worker pool was not fully saturated");
        }
        return saturation;
    }

    public void release() throws Exception {
        if (released) return;
        released = true;
        release.countDown();
        for (Future<?> task : held) task.get(5, TimeUnit.SECONDS);
        Utils.awaitBackgroundTasksForTests();
    }

    @Override public void close() throws Exception {
        release();
    }
}
