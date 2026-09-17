/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

@SuppressWarnings("unused")
public final class SwipeDirectionOverride {

    public static void interceptPageChange(Object viewPager, int page) {
        String action = Settings.SWIPE_LEFT_ACTION.get();
        if ("default".equals(action) || page == 0) {
            try {
                viewPager.getClass().getMethod("setCurrentItem", int.class)
                        .invoke(viewPager, page);
            } catch (Exception ex) {
                Logger.printException(() -> "Swipe override: fallback setCurrentItem failed", ex);
            }
            return;
        }
        if ("nothing".equals(action)) {
            Logger.printInfo(() -> "Swipe override: blocked page change to " + page);
            return;
        }
        Logger.printInfo(() -> "Swipe override: intercepted page " + page + " with action " + action);
    }

    private SwipeDirectionOverride() {}
}
