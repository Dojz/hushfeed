/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.content.ClipData;
import android.content.ClipboardManager;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class DevicePrivacyGuard {

    public static ClipData interceptClipboardRead(ClipboardManager manager) {
        Logger.printInfo(() -> "Device privacy guard: blocked a clipboard read");
        return null;
    }

    private DevicePrivacyGuard() {}
}
