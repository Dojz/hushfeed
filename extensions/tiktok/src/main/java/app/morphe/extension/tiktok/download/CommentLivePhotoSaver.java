/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The motion clip of a live photo in a comment.
 *
 * <p>TikTok's own Save photo on a comment hands its downloader the tapped photo's still, one
 * UrlModel, and nothing else: the clip a live photo carries in its livePhotoInfoModel never
 * lands (upstream #169). The patch calls in here as that save begins, once TikTok has the
 * comment and the photo's index in hand. A still is left to TikTok; a live photo gets its clip
 * fetched through the same transport every other save uses and published beside the still as
 * an MP4, with a banner of its own.
 */
public final class CommentLivePhotoSaver {
    private static final String HOOK_FAMILY = "comment live photo";

    private CommentLivePhotoSaver() {
    }

    /** A build that saves a comment's first photo with no index (46.2.3 did). */
    public static void saveClip(Object comment) {
        saveClip(comment, 0);
    }

    /** Called as TikTok saves the photo at {@code index} of {@code comment}'s images. */
    public static void saveClip(Object comment, int index) {
        try {
            if (!SettingsStatus.advancedDownloadsEnabled) return;
            List<String> urls = clipUrls(comment, index);
            HookStatus.bound(HOOK_FAMILY, urls.isEmpty() ? "still only" : "clip found");
            if (urls.isEmpty()) return;
            String id = Reflect.string(comment, "getCid", "cid");
            Context app = Utils.getContext().getApplicationContext();
            boolean submitted = MediaJobScheduler.submit("comment live photo", () -> {
                File temp = null;
                try {
                    MediaBudget.checkDiskSpace(app.getCacheDir(), -1L);
                    temp = MediaCache.createTempFile(app, "comment-live-", ".tmp");
                    RemoteMedia.fetch(urls, temp, RemoteMedia.Kind.VIDEO);
                    String name = DownloadFilenameFormatter.formatCommentMediaName(
                            "mp4", id == null || id.isEmpty() ? "live" : id + "-live");
                    String path = DownloadsPatch.getPhotoDownloadPath();
                    MediaFileWriter.Saved saved = MediaFileWriter.publishForResult(app, temp, name, "video/mp4", path, true);
                    SaveNotice.saved(L10n.f("Live photo clip saved to %1$s", path), saved);
                } catch (IOException | RuntimeException failure) {
                    Logger.printException(() -> "Could not save the live photo clip", failure);
                    Utils.showToastLong(L10n.t("The live photo's clip couldn't be saved. Try again."));
                } finally {
                    if (temp != null && !MediaCache.delete(temp)) {
                        Logger.printInfo(() -> "Could not remove the live photo temporary file");
                    }
                }
            });
            if (!submitted) Utils.showToastShort(L10n.t("Still saving the last one"));
        } catch (Throwable failure) {
            // A build whose comment model moved: TikTok's own save of the still goes on untouched.
            HookStatus.threw(HOOK_FAMILY, "comment live photo", failure);
        }
    }

    /** The clip's addresses for the photo at {@code index}, or none for a still. */
    static List<String> clipUrls(Object comment, int index) {
        Object images = Reflect.property(comment, "getImageList", "imageList");
        if (!(images instanceof List) || index < 0 || index >= ((List<?>) images).size()) {
            return Collections.emptyList();
        }
        Object image = ((List<?>) images).get(index);
        Object model = image == null ? null : Reflect.property(image, "getLivePhotoInfoModel", "livePhotoInfoModel");
        Object urls = model == null ? null : Reflect.property(model, "getUrlList", "urlList");
        if (!(urls instanceof List)) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (Object url : (List<?>) urls) {
            if (url instanceof String && !((String) url).isEmpty()) result.add((String) url);
        }
        return result;
    }
}
