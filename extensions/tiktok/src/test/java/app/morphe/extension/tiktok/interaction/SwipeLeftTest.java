package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.MotionEvent;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

/**
 * Swipe-left controls. TikTok's main pager slides a left swipe on the feed to the creator's
 * profile; the setting holds that pager on its feed page while a gesture heads for the profile
 * and, set to comments, opens the video's comments instead, once a swipe. On 47.0.3 the pager
 * holds three pages, a side panel, the feed and the profile, and the feed is the one before the
 * last (the probe's pagerstate on the S22).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SwipeLeftTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok's main pager, by the two methods the extension reads. */
    public static final class Pager {
        int page = FEED;
        int pages = 3;

        public int getCurrentItem() {
            return page;
        }

        public Adapter getAdapter() {
            return new Adapter(pages);
        }
    }

    /** Its adapter, by its page count. */
    public static final class Adapter {
        private final int count;

        Adapter(int count) {
            this.count = count;
        }

        public int getCount() {
            return count;
        }
    }

    /** The feed page of a three-page pager: a side panel at 0, the feed at 1, the profile at 2. */
    private static final int FEED = 1;
    private static final int PROFILE = 2;

    private Runnable original;
    private int opened;
    private final Pager pager = new Pager();

    @Before public void setUp() {
        original = GestureActions.swipeCommentsOpener;
        GestureActions.swipeCommentsOpener = () -> opened++;
    }

    @After public void tearDown() {
        GestureActions.swipeCommentsOpener = original;
        Settings.SWIPE_LEFT_ACTION.resetToDefault();
    }

    @Test public void tiktoksDefaultPagesAsItAlwaysDid() {
        assertEquals("default", Settings.SWIPE_LEFT_ACTION.get());
        pager.page = FEED;
        assertTrue(GestureActions.allowProfileSwipe(pager));
        pager.page = PROFILE;
        assertTrue(GestureActions.allowProfileSwipe(pager));
    }

    @Test public void doNothingAndCommentsHoldOnlyTheFeedPage() {
        for (String action : new String[]{"nothing", "comments"}) {
            Settings.SWIPE_LEFT_ACTION.save(action);
            pager.pages = 3;
            pager.page = FEED;
            // Every question below comes during a swipe heading left, for the profile.
            heading(900, 600);
            assertFalse(action + ": the feed page stays put", GestureActions.allowProfileSwipe(pager));
            pager.page = PROFILE;
            assertTrue(action + ": the swipe back from the profile stays TikTok's", GestureActions.allowProfileSwipe(pager));
            pager.page = 0;
            assertTrue(action + ": the side panel pages as usual", GestureActions.allowProfileSwipe(pager));
            assertTrue(action + ": a pager whose page can't be read pages as usual",
                    GestureActions.allowProfileSwipe(new Object()));
            pager.pages = 2;
            pager.page = 0;
            assertFalse(action + ": without the side panel the feed is page 0", GestureActions.allowProfileSwipe(pager));
            pager.page = 1;
            assertTrue(action + ": and the profile page 1", GestureActions.allowProfileSwipe(pager));
            pager.pages = 3;
            pager.page = FEED;
        }
    }

    /**
     * TikTok's pager asks after every event, so the hold follows the gesture: the touch down pages
     * as usual (TikTok records where the gesture starts), a swipe to the right still opens the side
     * panel, and one that turns back past where it started is let go.
     */
    @Test public void onlyAGestureHeadingForTheProfileIsHeld() {
        for (String action : new String[]{"nothing", "comments"}) {
            Settings.SWIPE_LEFT_ACTION.save(action);
            pager.page = FEED;
            long start = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, start, 500, 1000);
            assertTrue(action + ": at the touch down", GestureActions.allowProfileSwipe(pager));
            heading(200, 700);
            assertTrue(action + ": a swipe to the right, for the side panel", GestureActions.allowProfileSwipe(pager));
            heading(900, 600);
            assertFalse(action + ": a swipe to the left, for the profile", GestureActions.allowProfileSwipe(pager));
            send(MotionEvent.ACTION_MOVE, start, 950, 1000);
            assertTrue(action + ": turned back past where it started", GestureActions.allowProfileSwipe(pager));
            send(MotionEvent.ACTION_UP, start, 950, 1000);
        }
    }

    /** In a right-to-left layout TikTok's pager runs the other way: the profile is to the right. */
    @Test public void inARightToLeftLayoutTheProfileIsToTheRight() {
        Locale before = Locale.getDefault();
        Locale.setDefault(new Locale("ar"));
        try {
            // The hold under "do nothing", so the gestures that ask it open nothing themselves.
            Settings.SWIPE_LEFT_ACTION.save("nothing");
            pager.page = FEED;
            heading(200, 700);
            assertFalse("a swipe to the right is held", GestureActions.allowProfileSwipe(pager));
            heading(900, 600);
            assertTrue("a swipe to the left pages as usual", GestureActions.allowProfileSwipe(pager));
            Settings.SWIPE_LEFT_ACTION.save("comments");
            swipe(900, 1000, 200, 1000);
            assertEquals("a swipe to the left opens nothing", 0, opened);
            swipe(200, 1000, 900, 1000);
            assertEquals("a swipe to the right opens the comments", 1, opened);
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test public void aLeftSwipeFromTheFeedOpensCommentsOnce() {
        Settings.SWIPE_LEFT_ACTION.save("comments");
        swipe(900, 1000, 890, 1002, 600, 1010, 200, 1020);
        assertEquals("one swipe, one press, however far it goes", 1, opened);
    }

    @Test public void onlyAClearlySidewaysLeftSwipeCounts() {
        Settings.SWIPE_LEFT_ACTION.save("comments");
        swipe(200, 1000, 800, 1000);
        assertEquals("a right swipe", 0, opened);
        swipe(900, 1000, 500, 1400);
        assertEquals("more down than sideways", 0, opened);
        swipe(900, 1000, 890, 1000);
        assertEquals("a nudge", 0, opened);
        pager.page = PROFILE;
        swipe(900, 1000, 200, 1000);
        assertEquals("from the profile page", 0, opened);
        pager.page = 0;
        swipe(900, 1000, 200, 1000);
        assertEquals("from the side panel", 0, opened);
        pager.page = FEED;
    }

    @Test public void theOtherChoicesOpenNothing() {
        for (String action : new String[]{"default", "nothing"}) {
            Settings.SWIPE_LEFT_ACTION.save(action);
            swipe(900, 1000, 200, 1000);
            assertEquals(action, 0, opened);
        }
    }

    @Test public void eachNewSwipeCountsAgain() {
        Settings.SWIPE_LEFT_ACTION.save("comments");
        swipe(900, 1000, 200, 1000);
        swipe(900, 1000, 200, 1000);
        assertEquals(2, opened);
    }

    /** A swipe in progress: DOWN at one x, a MOVE to the other, no UP yet. */
    private void heading(float fromX, float toX) {
        long start = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, start, fromX, 1000);
        send(MotionEvent.ACTION_MOVE, start, toX, 1000);
    }

    /** DOWN at the first point, a MOVE at each point after it, then UP at the last. */
    private void swipe(float... points) {
        long start = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, start, points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) send(MotionEvent.ACTION_MOVE, start, points[i], points[i + 1]);
        send(MotionEvent.ACTION_UP, start, points[points.length - 2], points[points.length - 1]);
    }

    private void send(int action, long start, float x, float y) {
        MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0);
        try {
            GestureActions.onMainPagerTouch(pager, event);
        } finally {
            event.recycle();
        }
    }
}
