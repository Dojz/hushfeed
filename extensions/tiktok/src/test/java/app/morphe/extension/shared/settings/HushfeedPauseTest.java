/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Looper;

import app.morphe.extension.shared.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.util.concurrent.TimeUnit;

/** Three crashed starts in a row, the marker file and the way back, for Pause Hushfeed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HushfeedPauseTest {
    private Context context;
    private File record;
    private File streak;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        HushfeedPause.resetForTests();
        record = new File(context.getFilesDir(), HushfeedPause.START_RECORD_NAME);
        streak = new File(context.getFilesDir(), HushfeedPause.CRASH_STREAK_NAME);
        record.delete();
        streak.delete();
        File marker = HushfeedPause.markerFile(context);
        if (marker != null) marker.delete();
        BaseSettings.SAFE_MODE.save(false);
        BaseSettings.PAUSED.save(false);
    }

    @After public void tearDown() {
        setUp();
        HushfeedPause.resetForTests();
    }

    /** One start that ended the way [lastRecord] says, then this start. */
    private void start(String lastRecord) {
        HushfeedPause.resetForTests();
        if (lastRecord == null) record.delete();
        else HushfeedPause.write(record, lastRecord);
        HushfeedPause.onProcessStart(context);
    }

    @Test public void threeStartsThatCrashedInTheirFirstMinuteTurnSafeModeOn() {
        start(null);
        assertFalse(Setting.isPaused());

        start("4101 1000 crashed");
        assertEquals("1", HushfeedPause.read(streak));
        start("4102 2000 crashed");
        assertEquals("2", HushfeedPause.read(streak));
        assertFalse("two crashes are not yet a loop", Setting.isPaused());

        start("4103 3000 crashed");
        assertTrue("the fourth start runs in safe mode", Setting.isPaused());
        assertEquals(HushfeedPause.Reason.CRASH_LOOP, HushfeedPause.reason());
        assertTrue(BaseSettings.SAFE_MODE.savedValue());
        assertEquals("the count starts again once safe mode is on", "0", HushfeedPause.read(streak));

        // Safe mode holds for every later start until someone turns Hushfeed back on.
        start(null);
        assertEquals(HushfeedPause.Reason.CRASH_LOOP, HushfeedPause.reason());
    }

    @Test public void aStartThatDiedWithoutACrashEndsTheStreak() {
        start("4101 1000 crashed");
        start("4102 2000 crashed");
        assertEquals("2", HushfeedPause.read(streak));

        // Swiped away inside its first minute: no crash mark, and the streak is over.
        start("4103 3000");
        assertEquals("0", HushfeedPause.read(streak));
        start("4104 4000 crashed");
        assertEquals("1", HushfeedPause.read(streak));
        assertFalse(Setting.isPaused());
    }

    @Test public void aStartThatRanForAMinuteClearsItsRecordAndTheStreak() {
        start("4101 1000 crashed");
        assertTrue("this start left its own record", record.exists());
        shadowOf(Looper.getMainLooper()).idleFor(HushfeedPause.START_WINDOW_MS + 1, TimeUnit.MILLISECONDS);
        assertFalse("the record of a start that survived is gone", record.exists());
        assertEquals("0", HushfeedPause.read(streak));
    }

    @Test public void aCrashAndroidHoldsOpenPastTheMinuteStillCounts() {
        start("4101 1000 crashed");
        start("4102 2000 crashed");
        assertEquals("2", HushfeedPause.read(streak));

        // This start crashes off the main thread, and Android holds it open behind "keeps
        // stopping" while the main looper runs on past the minute.
        HushfeedPause.markCrash();
        HushfeedPause.crashState = () -> true;
        shadowOf(Looper.getMainLooper()).idleFor(HushfeedPause.START_WINDOW_MS + 1, TimeUnit.MILLISECONDS);
        assertTrue("the minute cleared the record of a start that crashed", record.exists());
        assertEquals("2", HushfeedPause.read(streak));

        // The next start judges the record this one left: the third crash in a row.
        HushfeedPause.resetForTests();
        HushfeedPause.onProcessStart(context);
        assertEquals(HushfeedPause.Reason.CRASH_LOOP, HushfeedPause.reason());
    }

    @Test public void anExceptionAHandlerSwallowedDoesNotEndTheStart() {
        start("4101 1000 crashed");
        assertEquals("1", HushfeedPause.read(streak));
        HushfeedPause.markCrash();
        HushfeedPause.crashState = () -> false;
        shadowOf(Looper.getMainLooper()).idleFor(HushfeedPause.START_WINDOW_MS + 1, TimeUnit.MILLISECONDS);
        assertFalse("a start still running after the minute kept its record", record.exists());
        assertEquals("0", HushfeedPause.read(streak));
    }

    @Test public void onlyACrashInsideTheFirstMinuteMarksTheStart() {
        start(null);
        ShadowSystemClock.advanceBy(HushfeedPause.START_WINDOW_MS - 1000, TimeUnit.MILLISECONDS);
        HushfeedPause.markCrash();
        assertTrue("a crash inside the minute was not marked",
                HushfeedPause.read(record).trim().endsWith("crashed"));

        // The clock runs past the minute before the looper gets to clear the record: a crash
        // this late is not a start crash.
        start(null);
        ShadowSystemClock.advanceBy(HushfeedPause.START_WINDOW_MS + 1000, TimeUnit.MILLISECONDS);
        HushfeedPause.markCrash();
        assertTrue(record.exists());
        assertFalse("a crash after the minute was marked",
                HushfeedPause.read(record).trim().endsWith("crashed"));
    }

    @Test @Config(sdk = 30)
    public void android11CountsCrashesNativeCrashesAndHangsButNotBeingSwipedAway() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        String process = context.getPackageName();
        shadowOf(manager).addApplicationExitInfo(process, 5101, ApplicationExitInfo.REASON_CRASH, 1);
        shadowOf(manager).addApplicationExitInfo(process, 5102, ApplicationExitInfo.REASON_CRASH_NATIVE, 1);
        shadowOf(manager).addApplicationExitInfo(process, 5103, ApplicationExitInfo.REASON_ANR, 1);
        shadowOf(manager).addApplicationExitInfo(process, 5104, ApplicationExitInfo.REASON_USER_REQUESTED, 0);
        shadowOf(manager).addApplicationExitInfo(process, 5105, ApplicationExitInfo.REASON_LOW_MEMORY, 0);

        assertTrue(HushfeedPause.diedYoungFromACrash(context, "5101 1000"));
        assertTrue(HushfeedPause.diedYoungFromACrash(context, "5102 1000"));
        assertTrue(HushfeedPause.diedYoungFromACrash(context, "5103 1000"));
        // Android's own reason wins over the handler's mark: a force stop is not a crash.
        assertFalse(HushfeedPause.diedYoungFromACrash(context, "5104 1000 crashed"));
        assertFalse(HushfeedPause.diedYoungFromACrash(context, "5105 1000"));
        // No reason on record for the process, so the handler's mark decides.
        assertTrue(HushfeedPause.diedYoungFromACrash(context, "5199 1000 crashed"));
        assertFalse(HushfeedPause.diedYoungFromACrash(context, "5199 1000"));
        assertFalse(HushfeedPause.diedYoungFromACrash(context, "not a pid"));
    }

    @Test public void theMarkerFilePausesEveryStartUntilItIsGone() throws Exception {
        File marker = HushfeedPause.markerFile(context);
        assertTrue(marker.getParentFile().exists() || marker.getParentFile().mkdirs());
        assertTrue(marker.createNewFile());

        start(null);
        assertEquals(HushfeedPause.Reason.MARKER_FILE, HushfeedPause.reason());
        assertTrue(Setting.isPaused());

        assertTrue("turning back on could not remove the marker", HushfeedPause.turnBackOn(context));
        assertFalse(marker.exists());
        start(null);
        assertEquals(HushfeedPause.Reason.NONE, HushfeedPause.reason());
    }

    @Test public void thePauseSwitchPausesTheNextStartAndTurningBackOnUndoesEverything() {
        BaseSettings.PAUSED.save(true);
        start(null);
        assertEquals(HushfeedPause.Reason.SWITCH, HushfeedPause.reason());

        BaseSettings.SAFE_MODE.save(true);
        HushfeedPause.write(streak, "2");
        assertTrue(HushfeedPause.turnBackOn(context));
        assertFalse(BaseSettings.PAUSED.savedValue());
        assertFalse(BaseSettings.SAFE_MODE.savedValue());
        assertEquals("0", HushfeedPause.read(streak));
        start(null);
        assertFalse(Setting.isPaused());
    }

    @Test public void theStreakFileSurvivesNonsense() {
        HushfeedPause.write(streak, "not a number");
        assertEquals(0, HushfeedPause.parseCount(HushfeedPause.read(streak)));
        assertEquals(0, HushfeedPause.parseCount("-4"));
        assertEquals(0, HushfeedPause.parseCount(null));
        assertNull(HushfeedPause.read(new File(context.getFilesDir(), "never-written")));
    }
}
