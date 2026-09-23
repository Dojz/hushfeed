package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * A completed save used to be a two-second toast carrying a folder path and offering nothing.
 * With a gallery row in hand it is the six-second action banner now, whose Open hands the row
 * to a viewer; without one (below API 29) the toast stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SaveNoticeTest {
    public static class HostActivity extends Activity {
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // No other window: the banner falls to the activity's content root.
        SaveNotice.windowRootsForTests = java.util.List.of();
    }

    @After public void tearDown() {
        Utils.setActivity(null);
        SaveNotice.windowRootsForTests = null;
    }

    @Test public void aSaveWithARowShowsTheBannerAndOpenStartsAViewerOnIt() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            Uri row = Uri.parse("content://media/external/audio/media/77");

            SaveNotice.saved("Sound saved to Music/TikTok", new MediaFileWriter.Saved("a.m4a", row));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            ViewGroup root = activity.findViewById(android.R.id.content);
            TextView open = find(root, "Open");
            assertNotNull("no banner with an Open action", open);
            assertNotNull("the banner lost the message", find(root, "Sound saved to Music/TikTok"));

            View clickable = open;
            while (clickable != null && !clickable.hasOnClickListeners()) {
                clickable = clickable.getParent() instanceof View ? (View) clickable.getParent() : null;
            }
            assertNotNull("nothing around Open takes the tap", clickable);
            assertTrue(clickable.performClick());
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("Open started nothing", started);
            assertEquals(Intent.ACTION_VIEW, started.getAction());
            assertEquals(row, started.getData());
            assertEquals("the viewer can't read the row",
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    started.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    @Test public void aSaveWithoutARowStaysAToast() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);

            SaveNotice.saved("Sound saved to Music/TikTok", new MediaFileWriter.Saved("a.m4a", null));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals("Sound saved to Music/TikTok", ShadowToast.getTextOfLatestToast());
            ViewGroup root = activity.findViewById(android.R.id.content);
            assertNull("a banner went up without a row to open", find(root, "Open"));
        }
    }

    /**
     * TikTok brings its share sheet back over the feed when a download finishes, and the sheet
     * is a window of its own: a banner on the activity stood underneath it, where the old toast
     * floated above. With another window up, the banner goes on that window.
     */
    @Test public void aSaveUnderAnotherWindowPutsTheBannerOnThatWindow() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            android.app.Dialog sheet = new android.app.Dialog(activity);
            sheet.setContentView(new android.widget.FrameLayout(activity));
            sheet.show();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View activityDecor = activity.getWindow().getDecorView();
            View sheetDecor = sheet.getWindow().getDecorView();
            SaveNotice.windowRootsForTests = java.util.List.of(activityDecor, sheetDecor);

            SaveNotice.saved("Story saved to DCIM/TikTok",
                    new MediaFileWriter.Saved("a.mp4", Uri.parse("content://media/external/video/media/9")));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertNotNull("the banner missed the window on top", find(sheetDecor, "Open"));
            assertNull("a second banner went on the activity underneath",
                    find(activity.findViewById(android.R.id.content), "Open"));
            sheet.dismiss();
        }
    }

    private static TextView find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = find(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
