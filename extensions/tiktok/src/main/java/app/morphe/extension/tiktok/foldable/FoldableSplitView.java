/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.foldable;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Map;
import java.util.WeakHashMap;

public final class FoldableSplitView {
    /**
     * The answer each activity's comment containers were built on: the first one TikTok asked
     * for while that activity was current. TikTok builds them once, when the feed attaches, and
     * its feed activity takes size changes itself, so they stay whatever the window does next.
     */
    private static final Map<Activity, Boolean> BUILT_FOR = new WeakHashMap<>();

    /** Stands in for {@link Activity#recreate()}, so a test can count. */
    interface Recreator {
        void recreate(Activity activity);
    }

    static Recreator recreator = Activity::recreate;

    private FoldableSplitView() { }

    public static boolean shouldForce(Activity activity, Configuration configuration) {
        if (!Settings.FOLDABLE_SPLIT_VIEW.get()) return false;
        if (activity == null) activity = Utils.getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        try {
            if (Build.VERSION.SDK_INT >= 24 && (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode())) return false;
            int width = configuration == null ? 0 : configuration.screenWidthDp;
            if (width <= 0 && Build.VERSION.SDK_INT >= 30) {
                float density = activity.getResources().getDisplayMetrics().density;
                if (density > 0) width = Math.round(activity.getWindowManager().getCurrentWindowMetrics().getBounds().width() / density);
            }
            if (width <= 0) width = activity.getResources().getConfiguration().screenWidthDp;
            int threshold = Math.max(320, Math.min(1600, Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.get()));
            return width >= threshold;
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not determine the comment panel width", error);
            return false;
        }
    }

    public static boolean shouldForceContainer() {
        Activity activity = Utils.getActivity();
        boolean force = shouldForce(activity, null);
        if (activity != null) {
            synchronized (BUILT_FOR) {
                if (!BUILT_FOR.containsKey(activity)) BUILT_FOR.put(activity, force);
            }
        }
        return force;
    }

    /**
     * First thing in the feed activity's configuration change (issue #26). An unfold reaches the
     * feed as a size change rather than as a new activity, so the comment containers TikTok built
     * for the folded width stayed until TikTok was started again. When the width crosses the
     * threshold, the activity is built again, once per crossing. A window beside other apps is
     * left to TikTok, and with the switch off nothing is rebuilt.
     */
    public static void onConfigurationChanged(Activity activity, Configuration configuration) {
        if (activity == null || !Settings.FOLDABLE_SPLIT_VIEW.get()) return;
        if (Build.VERSION.SDK_INT >= 24 && (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode())) return;
        Boolean builtFor;
        synchronized (BUILT_FOR) {
            builtFor = BUILT_FOR.get(activity);
        }
        // Never asked, so nothing was built for the other width.
        if (builtFor == null || shouldForce(activity, configuration) == builtFor) return;
        synchronized (BUILT_FOR) {
            BUILT_FOR.remove(activity);
        }
        Logger.printInfo(() -> "Split view: the window crossed the width threshold, so the feed is built again");
        try {
            recreator.recreate(activity);
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not build the feed again for the new window width", error);
        }
    }

    static void forgetForTests() {
        synchronized (BUILT_FOR) {
            BUILT_FOR.clear();
        }
        recreator = Activity::recreate;
    }
}
