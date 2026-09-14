package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OverlayControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test public void theInstalledBlockButtonDrawsBothTheRingAndTheSlash() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Method factory = BlockAuthorOverlay.class.getDeclaredMethod("createButton", Activity.class);
        factory.setAccessible(true);
        View button = (View) factory.invoke(null, activity);
        android.graphics.drawable.LayerDrawable layers =
                (android.graphics.drawable.LayerDrawable) button.getBackground();
        assertTrue(layers.getDrawable(1) instanceof BlockGlyphDrawable);
        int size = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY);
        button.measure(size, size);
        button.layout(0, 0, 100, 100);
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        button.draw(new Canvas(bitmap));
        assertNearWhite("the block symbol lost its diagonal bar", bitmap.getPixel(50, 50));
        assertNearWhite("the block symbol lost its ring", bitmap.getPixel(79, 50));
        assertNotEquals("the symbol became a filled disc", Color.WHITE, bitmap.getPixel(50, 40));
        assertEquals("the round button filled its transparent corner", 0, Color.alpha(bitmap.getPixel(0, 0)));
        bitmap.recycle();
        activity.finish();
    }

    private static void assertNearWhite(String message, int pixel) {
        // Edge coverage is fractional at a diagonal even at the centre of a 2px stroke.
        assertTrue(message + ": " + Integer.toHexString(pixel),
                Color.alpha(pixel) >= 225 && Color.red(pixel) >= 225
                        && Color.green(pixel) >= 225 && Color.blue(pixel) >= 225);
    }

    @Test public void renderAccessibleOverlayControls() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Bitmap bitmap = Bitmap.createBitmap(304, 88, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(24, 24, 27));
        String[] methods = {"createButton", "createLocalHideButton", "createSoundButton", "createNotInterestedButton"};
        for (int i = 0; i < methods.length; i++) {
            Method factory = BlockAuthorOverlay.class.getDeclaredMethod(methods[i], Activity.class);
            factory.setAccessible(true);
            View view = (View) factory.invoke(null, activity);
            assertNotNull(view.getContentDescription());
            assertTrue(view.hasOnClickListeners());
            assertTrue(view.isLongClickable());
            AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
            assertEquals(android.widget.Button.class.getName(), node.getClassName());
            node.recycle();
            int size = View.MeasureSpec.makeMeasureSpec(56, View.MeasureSpec.EXACTLY);
            view.measure(size, size);
            view.layout(0, 0, 56, 56);
            canvas.save();
            canvas.translate(16 + i * 72, 16);
            view.draw(canvas);
            canvas.restore();
        }
        String directory = System.getProperty("morphe.screenshotDir");
        if (directory != null) {
            File output = new File(directory, "overlay-controls.png");
            assertTrue(output.getParentFile().isDirectory() || output.getParentFile().mkdirs());
            try (FileOutputStream stream = new FileOutputStream(output)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
        }
        activity.finish();
    }

    @Test public void accessibilityLongClickCannotLeaveAControlInPointerDragMode() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Method factory = BlockAuthorOverlay.class.getDeclaredMethod("createButton", Activity.class);
        factory.setAccessible(true);
        View button = (View) factory.invoke(null, activity);
        activity.setContentView(button);
        java.util.concurrent.atomic.AtomicInteger clicks = new java.util.concurrent.atomic.AtomicInteger();
        button.setOnClickListener(view -> clicks.incrementAndGet());

        assertFalse(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null));
        java.lang.reflect.Field dragging = BlockAuthorOverlay.class.getDeclaredField("dragging");
        dragging.setAccessible(true);
        assertFalse(dragging.getBoolean(null));
        assertTrue(button.performClick());
        assertEquals(1, clicks.get());
        assertFalse(dragging.getBoolean(null));
    }

    @Test public void theFeedButtonsAreLaidOutFromTheLeftInEitherDirection() throws Exception {
        // Every position on these buttons is a pixel worked out from a raw touch and written to
        // leftMargin. A mirrored layout resolves START to RIGHT and then reads rightMargin, which
        // nothing sets, so the saved position was thrown away, a drag moved nothing sideways, and
        // the Not interested button, which differs from the block button only by leftMargin,
        // landed on top of it.
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        android.view.ViewGroup root = activity.findViewById(android.R.id.content);

        // The real attach path, so the params under test are the ones production writes.
        assertEquals("the overlay looks the activity up for itself", activity, Utils.getActivity());
        Method attach = BlockAuthorOverlay.class.getDeclaredMethod("attach", VideoAuthor.class);
        attach.setAccessible(true);
        int before = root.getChildCount();
        attach.invoke(null, new VideoAuthor("1", "sec", "someone", "7712345"));
        assertNotEquals("attach added nothing to the content root", before, root.getChildCount());

        String[] buttons = {"buttonReference", "localHideReference", "soundButtonReference",
                "notInterestedReference"};
        for (String name : buttons) {
            java.lang.reflect.Field held = BlockAuthorOverlay.class.getDeclaredField(name);
            held.setAccessible(true);
            View view = ((java.lang.ref.WeakReference<View>) held.get(null)).get();
            assertNotNull(name + " was never attached", view);

            // Asking the platform what a mirrored layout does with these exact params, rather
            // than laying the root out right-to-left, because a FrameLayout only resolves a
            // direction once it is attached to a real window and off a test that would pass
            // whichever gravity it was given.
            int gravity = ((FrameLayout.LayoutParams) view.getLayoutParams()).gravity;
            int mirrored = Gravity.getAbsoluteGravity(gravity, View.LAYOUT_DIRECTION_RTL)
                    & Gravity.HORIZONTAL_GRAVITY_MASK;
            assertEquals(name + " is mirrored away from the margin that positions it",
                    Gravity.LEFT, mirrored);
        }
    }

    @Test public void allFourFeedButtonsAreOneSizeAndOneShape() throws Exception {
        // They sit in a column on the feed, where a miss is a like or a follow on somebody's
        // video, and 44dp is under Android's own guidance with no TouchDelegate to make up the
        // difference. Not interested was also the only rounded rectangle of the four, over a
        // darker scrim, which on a column of four reads as a mistake rather than a distinction.
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        Method attach = BlockAuthorOverlay.class.getDeclaredMethod("attach", VideoAuthor.class);
        attach.setAccessible(true);
        attach.invoke(null, new VideoAuthor("1", "sec", "someone", "7712345"));

        int expected = app.morphe.extension.tiktok.settings.preference.SettingsUi
                .dp(activity, 48);
        String[] buttons = {"buttonReference", "localHideReference", "soundButtonReference",
                "notInterestedReference"};
        for (String name : buttons) {
            java.lang.reflect.Field held = BlockAuthorOverlay.class.getDeclaredField(name);
            held.setAccessible(true);
            View view = ((java.lang.ref.WeakReference<View>) held.get(null)).get();
            assertNotNull(name + " was never attached", view);

            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            assertEquals(name + " is not 48dp wide", expected, params.width);
            assertEquals(name + " is not 48dp tall", expected, params.height);

            // The block button draws its symbol over the disc rather than setting it as text,
            // because the font TikTok is using may not carry it, so its background is a layer
            // list with the disc underneath.
            android.graphics.drawable.Drawable background = view.getBackground();
            if (background instanceof android.graphics.drawable.LayerDrawable) {
                background = ((android.graphics.drawable.LayerDrawable) background)
                        .getDrawable(0);
            }
            android.graphics.drawable.GradientDrawable disc =
                    (android.graphics.drawable.GradientDrawable) background;
            assertEquals(name + " is not the round shape the others are",
                    android.graphics.drawable.GradientDrawable.OVAL, disc.getShape());
        }
    }

    @Test public void movingAndSavingOneFeedButtonDoesNotMoveTheOthers() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        android.view.ViewGroup root = activity.findViewById(android.R.id.content);

        Method attach = BlockAuthorOverlay.class.getDeclaredMethod("attach", VideoAuthor.class);
        attach.setAccessible(true);
        attach.invoke(null, new VideoAuthor("1", "sec", "someone", "7712345"));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        int spec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        root.measure(spec, spec);
        root.layout(0, 0, 1080, 1080);
        View block = held("buttonReference");
        View local = held("localHideReference");
        View sound = held("soundButtonReference");
        View feedback = held("notInterestedReference");
        Method move = BlockAuthorOverlay.class.getDeclaredMethod(
                "moveTo", View.class, android.view.ViewGroup.class, float.class, float.class);
        move.setAccessible(true);
        Method save = BlockAuthorOverlay.class.getDeclaredMethod(
                "savePosition", View.class, android.view.ViewGroup.class);
        save.setAccessible(true);

        int blockLeft = ((FrameLayout.LayoutParams) block.getLayoutParams()).leftMargin;
        int soundLeft = ((FrameLayout.LayoutParams) sound.getLayoutParams()).leftMargin;
        int feedbackLeft = ((FrameLayout.LayoutParams) feedback.getLayoutParams()).leftMargin;
        String oldBlock = Settings.BLOCK_AUTHOR_BUTTON_POSITION.get();
        String oldLocal = Settings.LOCAL_HIDE_BUTTON_POSITION.get();
        String oldSound = Settings.BLOCK_SOUND_BUTTON_POSITION.get();
        String oldFeedback = Settings.NOT_INTERESTED_BUTTON_POSITION.get();
        try {
            move.invoke(null, local, root, 123f, 456f);
            save.invoke(null, local, root);
            FrameLayout.LayoutParams localParams = (FrameLayout.LayoutParams) local.getLayoutParams();
            assertEquals(123, localParams.leftMargin);
            assertEquals(456, localParams.topMargin);
            assertEquals(blockLeft, ((FrameLayout.LayoutParams) block.getLayoutParams()).leftMargin);
            assertEquals(soundLeft, ((FrameLayout.LayoutParams) sound.getLayoutParams()).leftMargin);
            assertEquals(feedbackLeft,
                    ((FrameLayout.LayoutParams) feedback.getLayoutParams()).leftMargin);
            assertNotEquals(oldLocal, Settings.LOCAL_HIDE_BUTTON_POSITION.get());
            assertEquals(oldBlock, Settings.BLOCK_AUTHOR_BUTTON_POSITION.get());
            assertEquals(oldSound, Settings.BLOCK_SOUND_BUTTON_POSITION.get());
            assertEquals(oldFeedback, Settings.NOT_INTERESTED_BUTTON_POSITION.get());
        } finally {
            Settings.BLOCK_AUTHOR_BUTTON_POSITION.save(oldBlock);
            Settings.LOCAL_HIDE_BUTTON_POSITION.save(oldLocal);
            Settings.BLOCK_SOUND_BUTTON_POSITION.save(oldSound);
            Settings.NOT_INTERESTED_BUTTON_POSITION.save(oldFeedback);
        }
    }

    @SuppressWarnings("unchecked")
    private static View held(String fieldName) throws Exception {
        java.lang.reflect.Field field = BlockAuthorOverlay.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        View view = ((java.lang.ref.WeakReference<View>) field.get(null)).get();
        assertNotNull(fieldName + " was never attached", view);
        return view;
    }

    @Test public void theOverlaysFollowTheActivityTheHostRecreated() {
        Activity first = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(first);
        assertSame(first, Utils.getActivity());

        // The host recreates its main activity on a configuration change it does not swallow,
        // and the extension hook runs again for the new one. Keeping the first instance left
        // every overlay attaching to a window nobody was looking at, and isFinishing() reports
        // nothing for a recreated activity because it is destroyed rather than finishing.
        Activity second = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(second);

        assertSame("the overlays would still be drawing on the old window", second, Utils.getActivity());
    }

    @Test public void aSecondUndoBannerKeepsItsOwnSixSeconds() {
        // Block an author and hide one locally inside six seconds: the second banner replaced the
        // first, and the first banner's dismiss was still queued, so it took the second away
        // early and the Undo went with it.
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        android.view.ViewGroup root = activity.findViewById(android.R.id.content);
        var looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());

        BlockAuthorOverlay.showUndoBanner("first", () -> { });
        looper.idle();
        int withOne = root.getChildCount();
        assertTrue("the first banner was never shown", withOne > 0);

        looper.idleFor(java.time.Duration.ofSeconds(3));
        BlockAuthorOverlay.showUndoBanner("second", () -> { });
        looper.idle();

        looper.idleFor(java.time.Duration.ofSeconds(3));
        assertEquals("the first banner's timer took the second one away", withOne,
                root.getChildCount());

        // And the second banner still goes away on its own time rather than staying forever.
        looper.idleFor(java.time.Duration.ofSeconds(4));
        assertTrue("the banner never went away", root.getChildCount() < withOne);
    }
}
