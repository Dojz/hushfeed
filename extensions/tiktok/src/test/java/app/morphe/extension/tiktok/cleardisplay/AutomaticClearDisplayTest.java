package app.morphe.extension.tiktok.cleardisplay;

import static org.junit.Assert.*;
import android.os.Looper;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AutomaticClearDisplayTest {
    @After public void tearDown() {
        SettingsStatus.automaticClearDisplayEnabled = false;
    }
    public static class Event {
        public boolean LIZ;
        public int LIZIZ;
        Event(boolean clear, int type) { LIZ = clear; LIZIZ = type; }
    }
    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.AUTOMATIC_CLEAR_DISPLAY.save(false);
        Settings.CLEAR_DISPLAY.save(false);
        RememberClearDisplayPatch.firstFrame("reset", () -> true, value -> {});
        Settings.AUTOMATIC_CLEAR_DISPLAY.save(true);
        Settings.AUTOMATIC_CLEAR_DISPLAY_DELAY.save(1000);
    }
    @Test public void theAutomaticPathReportsClearDisplayEvenThoughItNeverWritesTheSetting() {
        // rememberClearDisplayEvent is the only writer of the setting, and it returns early
        // for anything posted from here, so the setting stays false through the whole
        // automatic path. Anything that needs to know whether the controls are hidden has
        // to ask for the live state instead.
        assertFalse(RememberClearDisplayPatch.isClearDisplayNow());

        List<Boolean> events = new ArrayList<>();
        RememberClearDisplayPatch.firstFrame("auto", () -> true, events::add);
        assertFalse("the controls are still up during the delay", RememberClearDisplayPatch.isClearDisplayNow());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        assertEquals(List.of(false, true), events);
        assertTrue("the controls are hidden now", RememberClearDisplayPatch.isClearDisplayNow());
        assertFalse("and the setting still says nothing", Settings.CLEAR_DISPLAY.get());

        // This is what the tab strip hide reads, which is why it cannot read the setting.
        assertNotEquals(RememberClearDisplayPatch.isClearDisplayNow(), Settings.CLEAR_DISPLAY.get());
    }

    @Test public void aTapThatLeavesClearDisplayIsReportedToo() {
        List<Boolean> events = new ArrayList<>();
        RememberClearDisplayPatch.firstFrame("auto", () -> true, events::add);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        assertTrue(RememberClearDisplayPatch.isClearDisplayNow());

        // TikTok posts its own event when the user taps to bring the controls back.
        RememberClearDisplayPatch.rememberClearDisplayEvent(new Event(false, 1));
        assertFalse(RememberClearDisplayPatch.isClearDisplayNow());
    }

    @Test public void waitsAndDoesNotRearmRepeatedFirstFrame() {
        List<Boolean> events = new ArrayList<>();
        RememberClearDisplayPatch.firstFrame("one", () -> true, events::add);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999));
        assertEquals(List.of(false), events);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertEquals(List.of(false, true), events);
        RememberClearDisplayPatch.firstFrame("one", () -> true, events::add);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of(false, true), events);
        assertFalse(RememberClearDisplayPatch.getClearDisplayState());
    }
    @Test public void newVideoAndManualRestoreCancelPendingWork() {
        List<Boolean> old = new ArrayList<>(), next = new ArrayList<>();
        RememberClearDisplayPatch.firstFrame("one", () -> true, old::add);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        RememberClearDisplayPatch.firstFrame("two", () -> true, next::add);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals(List.of(false), old);
        RememberClearDisplayPatch.rememberClearDisplayEvent(new Event(false, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of(false), next);
        RememberClearDisplayPatch.firstFrame("two", () -> true, next::add);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of(false), next);
        RememberClearDisplayPatch.firstFrame("three", () -> true, next::add);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(List.of(false, false, true), next);
    }
    @Test public void disabledOrStalePlaybackDoesNotHideAndManualMemorySurvives() {
        List<Boolean> events = new ArrayList<>();
        RememberClearDisplayPatch.firstFrame("one", () -> false, events::add);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        RememberClearDisplayPatch.firstFrame("two", () -> true, events::add);
        Settings.AUTOMATIC_CLEAR_DISPLAY.save(false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(List.of(false, false), events);
        RememberClearDisplayPatch.rememberClearDisplayEvent(new Event(true, 0));
        RememberClearDisplayPatch.rememberClearDisplayEvent(new Event(false, 3));
        assertTrue(RememberClearDisplayPatch.getClearDisplayState());
        RememberClearDisplayPatch.firstFrame("three", () -> true, events::add);
        assertEquals(List.of(false, false, true), events);
    }
    /**
     * Switched off while it had the controls hidden: TikTok shows them on the next video by
     * itself (S22, 2026-09-23), and the live state has to say so, or the tab strip hide keeps
     * TikTok's top strip away with the feature off. Once shown, later videos ask nothing more.
     */
    @Test public void switchingTheAutomaticPathOffShowsTheControlsOnTheNextVideo() {
        List<Boolean> events = new ArrayList<>();
        RememberClearDisplayPatch.firstFrame("one", () -> true, events::add);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        assertTrue(RememberClearDisplayPatch.isClearDisplayNow());

        Settings.AUTOMATIC_CLEAR_DISPLAY.save(false);
        RememberClearDisplayPatch.firstFrame("two", () -> true, events::add);
        assertEquals(List.of(false, true, false), events);
        assertFalse(RememberClearDisplayPatch.isClearDisplayNow());

        RememberClearDisplayPatch.firstFrame("three", () -> true, events::add);
        assertEquals(List.of(false, true, false), events);
    }

    /**
     * A clear display the user chose is theirs, even where it isn't remembered: while Hushfeed
     * is paused TikTok's own clear mode is not saved, and switching nothing on or off must not
     * bring the controls back over it.
     */
    @Test public void aClearDisplayTheUserChoseIsLeftAloneWhilePaused() {
        List<Boolean> events = new ArrayList<>();
        // The automatic path hides the controls and the user brings them back with TikTok's X,
        RememberClearDisplayPatch.firstFrame("one", () -> true, events::add);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        RememberClearDisplayPatch.rememberClearDisplayEvent(new Event(false, 0));
        app.morphe.extension.shared.settings.PausedProcess.set(true);
        try {
            // then, paused, hides them with TikTok's own mode, which a paused process doesn't save.
            RememberClearDisplayPatch.rememberClearDisplayEvent(new Event(true, 0));
            assertTrue(RememberClearDisplayPatch.isClearDisplayNow());
            RememberClearDisplayPatch.firstFrame("next", () -> true, events::add);
            assertEquals("nothing asked of TikTok on the next video", List.of(false, true), events);
            assertTrue(RememberClearDisplayPatch.isClearDisplayNow());
        } finally {
            app.morphe.extension.shared.settings.PausedProcess.set(false);
        }
    }

    @Test public void disablingAndReenablingBeforeTheDeadlineCancelsTheTimer() {
        List<Boolean> events = new ArrayList<>();
        RememberClearDisplayPatch.firstFrame("one", () -> true, events::add);
        Settings.AUTOMATIC_CLEAR_DISPLAY.save(false);
        Settings.AUTOMATIC_CLEAR_DISPLAY.save(true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of(false), events);
    }
    @Test public void leavingAndReturningBeforeTheDeadlineCancelsTheTimer() {
        try (var owner = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            RememberClearDisplayPatch.observeWindow(owner.get().getWindow().getDecorView());
            List<Boolean> events = new ArrayList<>();
            RememberClearDisplayPatch.firstFrame("one", () -> true, events::add);
            owner.windowFocusChanged(false);
            owner.windowFocusChanged(true);
            RememberClearDisplayPatch.firstFrame("one", () -> true, events::add);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(List.of(false), events);
        }
    }
    @Test public void standaloneControlsShowDelayInMilliseconds() throws Exception {
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.interaction.GestureActionsTest.TestActivity.class).setup()) {
            var activity = owner.get();
            Utils.setContext(activity);
            Utils.setIsDarkModeEnabled(true);
            SettingsStatus.automaticClearDisplayEnabled = true;
            var screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("automatic_clear_display"));
            // "ms" is a developer's unit. The row says the word.
            assertTrue(screen.findPreference("automatic_clear_display_delay")
                    .getSummary().toString().contains("1000 milliseconds"));
            activity.setPreferenceScreen(screen);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            app.morphe.extension.tiktok.UiCapture.save(activity.getWindow().getDecorView(), "clear-display-settings.png");
        }
    }
}
