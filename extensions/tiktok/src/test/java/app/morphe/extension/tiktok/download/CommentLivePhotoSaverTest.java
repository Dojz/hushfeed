/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The clip of a live photo in a comment: read off the comment's model the way TikTok's own
 * classes hold it (a getter for the image list and the live-photo model, a public field for
 * the clip's addresses), a still leaving nothing to fetch, and the hook staying silent while
 * the Downloads patch is off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentLivePhotoSaverTest {
    /** The clip's addresses, a public field on TikTok's model with no getter. */
    public static final class LivePhotoInfo {
        public final List<String> urlList;
        public LivePhotoInfo(List<String> urlList) { this.urlList = urlList; }
    }

    public static final class Image {
        private final LivePhotoInfo live;
        public Image(LivePhotoInfo live) { this.live = live; }
        public LivePhotoInfo getLivePhotoInfoModel() { return live; }
    }

    public static final class Comment {
        private final List<Image> images;
        public Comment(List<Image> images) { this.images = images; }
        public List<Image> getImageList() { return images; }
        public String getCid() { return "7000000000000000001"; }
    }

    private boolean downloadsWereEnabled;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        downloadsWereEnabled = SettingsStatus.advancedDownloadsEnabled;
    }

    @After public void tearDown() {
        SettingsStatus.advancedDownloadsEnabled = downloadsWereEnabled;
        HookStatus.clear();
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test public void theClipsAddressesComeOffTheTappedPhoto() {
        Comment comment = new Comment(Arrays.asList(
                new Image(null),
                new Image(new LivePhotoInfo(Arrays.asList("https://v.example/clip-a.mp4", "", "https://v.example/clip-b.mp4")))));
        assertEquals(Arrays.asList("https://v.example/clip-a.mp4", "https://v.example/clip-b.mp4"),
                CommentLivePhotoSaver.clipUrls(comment, 1));
    }

    @Test public void aStillAnIndexOffTheEndAndAnUnknownModelLeaveNothingToFetch() {
        Comment comment = new Comment(Arrays.asList(new Image(null), new Image(new LivePhotoInfo(null))));
        assertTrue(CommentLivePhotoSaver.clipUrls(comment, 0).isEmpty());
        assertTrue(CommentLivePhotoSaver.clipUrls(comment, 1).isEmpty());
        assertTrue(CommentLivePhotoSaver.clipUrls(comment, 2).isEmpty());
        assertTrue(CommentLivePhotoSaver.clipUrls(comment, -1).isEmpty());
        assertTrue(CommentLivePhotoSaver.clipUrls(new Object(), 0).isEmpty());
        assertTrue(CommentLivePhotoSaver.clipUrls(null, 0).isEmpty());
    }

    @Test public void aStillIsNotedAndLeftToTikTok() {
        SettingsStatus.advancedDownloadsEnabled = true;
        CommentLivePhotoSaver.saveClip(new Comment(Arrays.asList(new Image(null))), 0);
        assertTrue(report(), report().contains("comment live photo: 1 found, 0 missing"));
    }

    @Test public void withTheDownloadsPatchOffNothingIsReadOrNoted() {
        SettingsStatus.advancedDownloadsEnabled = false;
        CommentLivePhotoSaver.saveClip(new Comment(Arrays.asList(
                new Image(new LivePhotoInfo(Arrays.asList("https://v.example/clip.mp4"))))), 0);
        assertTrue(report(), !report().contains("comment live photo"));
    }
}
