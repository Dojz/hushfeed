package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.*;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The surprise TikTok's comment surprise struct is built with, which every popup ad path reads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentPopupAdsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.HIDE_COMMENT_EGGS.resetToDefault();
    }

    @Test public void theSwitchOnDropsTheSurpriseAndOffKeepsIt() {
        Object surprise = new Object();
        Settings.HIDE_COMMENT_EGGS.save(true);
        assertNull(CommentTools.commentSurprise(surprise));
        Settings.HIDE_COMMENT_EGGS.save(false);
        assertSame(surprise, CommentTools.commentSurprise(surprise));
    }

    @Test public void noSurpriseStaysNone() {
        Settings.HIDE_COMMENT_EGGS.save(false);
        assertNull(CommentTools.commentSurprise(null));
        Settings.HIDE_COMMENT_EGGS.save(true);
        assertNull(CommentTools.commentSurprise(null));
    }

    @Test public void pausedTheSurpriseIsTikToks() {
        Object surprise = new Object();
        Settings.HIDE_COMMENT_EGGS.save(true);
        PausedProcess.set(true);
        assertSame(surprise, CommentTools.commentSurprise(surprise));
    }
}
