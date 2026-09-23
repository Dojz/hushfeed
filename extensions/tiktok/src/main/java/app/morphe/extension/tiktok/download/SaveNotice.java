/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Says a save landed, with a way to it. The two-second toast carried a folder path and offered
 * nothing, so the reader went hunting through the gallery; a completed save now shows the same
 * words on the action banner, up for six seconds, whose Open hands the saved row to a viewer.
 * The toast stays for a save without a row to open: below API 29, and wherever no screen is up
 * to draw the banner on, which the banner path itself falls back from.
 */
final class SaveNotice {
    private SaveNotice() {
    }

    /** Announces a completed save of {@code saved}, or by toast alone when it has no row. */
    static void saved(String message, MediaFileWriter.Saved saved) {
        Uri uri = saved == null ? null : saved.uri;
        if (uri == null) {
            Utils.showToastShort(message);
            return;
        }
        BlockAuthorOverlay.showActionBanner(message, L10n.t("Open"), () -> open(uri));
    }

    private static void open(Uri uri) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, uri);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Activity activity = Utils.getActivity();
            if (activity != null) {
                activity.startActivity(view);
                return;
            }
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Utils.getContext().startActivity(view);
        } catch (ActivityNotFoundException nothingOpensIt) {
            Utils.showToastShort(L10n.t("No app on this phone opens that file"));
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not open the saved file", failure);
        }
    }
}
