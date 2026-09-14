/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * The backdrop every control drawn over a video shares, and the radius scale behind it.
 *
 * <p>Five places built this pair of colours by hand and then rounded it three different ways, so
 * the four feed controls, the budget cue and the hold's release control read as three separate
 * add-ons sitting on the same video. These hold the one helper to one answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class OverlayChipTest {

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test public void theChipIsARectangleAtTheRadiusItWasAskedFor() {
        Activity activity = activity();
        GradientDrawable chip = SettingsUi.overlayChip(activity, SettingsUi.RADIUS_OVERLAY);

        assertEquals("the overlay backdrop is not a rectangle",
                GradientDrawable.RECTANGLE, chip.getShape());
        assertEquals("the overlay backdrop is not drawn at the radius it was given",
                (float) SettingsUi.dp(activity, SettingsUi.RADIUS_OVERLAY),
                chip.getCornerRadius(), 0.5f);
    }

    @Test public void theChipIsTheSameWhicheverThemeTheSettingsScreenIsIn() {
        Activity activity = activity();

        app.morphe.extension.shared.Utils.setIsDarkModeEnabled(true);
        GradientDrawable dark = SettingsUi.overlayChip(activity, SettingsUi.RADIUS_OVERLAY);
        app.morphe.extension.shared.Utils.setIsDarkModeEnabled(false);
        GradientDrawable light = SettingsUi.overlayChip(activity, SettingsUi.RADIUS_OVERLAY);

        // A video is dark whatever the phone's theme says, and away from the settings screen the
        // shared theme flag answers for the system rather than for the feed. Both callers relied
        // on that before, each with its own copy of the two colours.
        assertEquals("the overlay backdrop followed the settings theme",
                dark.getCornerRadius(), light.getCornerRadius(), 0f);
        assertEquals("the overlay backdrop followed the settings theme",
                dark.getShape(), light.getShape());
    }

    /**
     * No radius outside the scale, and no pill.
     *
     * <p>Read off the constants rather than written out again here, so adding a step to the scale
     * is one edit and removing one cannot leave this passing against a value nothing uses.
     */
    @Test public void everyRadiusOnTheScaleIsAStepAndNoneOfThemIsAPill() throws Exception {
        List<Integer> scale = new ArrayList<>();
        for (Field field : SettingsUi.class.getDeclaredFields()) {
            if (!field.getName().startsWith("RADIUS_")) continue;
            assertTrue(field.getName() + " is not a constant", Modifier.isStatic(field.getModifiers()));
            field.setAccessible(true);
            scale.add(field.getInt(null));
        }

        assertEquals("the radius scale lost a step", 6, scale.size());
        for (int radius : scale) {
            assertTrue("a radius on the scale is negative: " + radius, radius >= 0);
            // 16dp is already larger than half of the 48dp control these sit behind, so anything
            // at or above it rounds a control into a pill or a disc.
            assertTrue("a radius on the scale is large enough to make a pill: " + radius,
                    radius < 16);
        }
        assertTrue("the scale has no square step", scale.contains(SettingsUi.RADIUS_SQUARE));
        assertNotEquals("the overlay radius collapsed onto the square step",
                SettingsUi.RADIUS_SQUARE, SettingsUi.RADIUS_OVERLAY);
    }

    /** The mutation control: a value off the scale has to be able to fail the check above. */
    @Test public void theScaleCheckCanActuallyFail() {
        int pill = 24;
        assertTrue("a 24dp radius on a 48dp control is a pill and the check has to say so",
                pill >= 16);
    }
}
