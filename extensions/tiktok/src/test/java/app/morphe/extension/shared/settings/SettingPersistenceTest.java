package app.morphe.extension.shared.settings;

import static org.junit.Assert.assertEquals;

import app.morphe.extension.shared.Utils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** A failed disk write must not leave the process claiming a setting was saved. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SettingPersistenceTest {
    private static final class FailingSetting extends Setting<String> {
        FailingSetting(String key) {
            super(key, "before", false, false, null, null);
        }

        @Override protected void load() {
            value = defaultValue;
        }

        @Override protected void setValueFromString(String newValue) {
            value = newValue;
        }

        @Override protected void saveToPreferences() {
            throw new IllegalStateException("injected commit failure");
        }

        @Override public String get() {
            return value;
        }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void failedWriteRestoresTheValueThatIsStillOnDisk() {
        FailingSetting setting = new FailingSetting("persistence_failure_" + System.nanoTime());

        setting.save("after");

        assertEquals("a failed write escaped into the live setting", "before", setting.get());
    }
}
