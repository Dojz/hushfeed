package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SettingsUiTest {
    public static class DialogActivity extends Activity {
        @Override
        protected void onCreate(android.os.Bundle state) {
            boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
            setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                    : android.R.style.Theme_Material_Light_NoActionBar);
            Utils.setIsDarkModeEnabled(dark);
            super.onCreate(state);
        }
    }

    @After
    public void restoreDarkMode() {
        Utils.setIsDarkModeEnabled(true);
    }

    @Test
    public void sharedTextControlsExposeTheirDisabledStateAndActionRole() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);

            TextView label = SettingsUi.text(
                    activity, "Save", 14, SettingsUi.accent(), android.graphics.Typeface.BOLD);
            assertEquals(SettingsUi.accent(), label.getCurrentTextColor());
            label.setEnabled(false);
            assertEquals("a disabled text control kept its active colour",
                    SettingsUi.textDisabled(), label.getCurrentTextColor());

            TextView action = SettingsUi.text(
                    activity, "Save", 14, SettingsUi.accent(), android.graphics.Typeface.BOLD);
            SettingsUi.styleTextAction(action, true);
            assertEquals(android.widget.Button.class.getName(),
                    action.createAccessibilityNodeInfo().getClassName());

            EditText field = new EditText(activity);
            SettingsUi.styleEditText(field);
            field.setEnabled(false);
            assertEquals("a disabled field kept its active text colour",
                    SettingsUi.textDisabled(), field.getCurrentTextColor());
        }
    }

    @Test
    public void sharedDialogHeadingAndResultStatusCarryAccessibilitySemantics() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TextView heading = SettingsUi.text(
                    activity, "Dialog title", 20, SettingsUi.textPrimary(), 1);
            SettingsUi.markDialogHeading(heading);
            assertTrue(heading.isAccessibilityHeading());

            TextView count = SettingsUi.resultCount(activity, "test_result_count");
            SettingsUi.setResultCount(count, 2);
            assertEquals("2 results", count.getText().toString());
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    count.getAccessibilityLiveRegion());
        }
    }

    @Test
    public void darkSingleChoiceUsesOneRadioAndNativeSelection() throws Exception {
        assertSingleChoice("dialogs/dark/single-choice.png");
    }

    @Test
    @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightSingleChoiceUsesOneRadioAndNativeSelection() throws Exception {
        assertSingleChoice("dialogs/light/single-choice.png");
    }

    private void assertSingleChoice(String screenshotPath) throws Exception {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Playback speed")
                    .setSingleChoiceItems(new String[]{"0.5x", "1x"}, 0, (ignored, which) -> {})
                    .setNegativeButton("Cancel", null)
                    .create();
            dialog.show();
            SettingsUi.styleStandardAlertDialog(dialog);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            ListView list = dialog.getListView();
            assertEquals(ListView.CHOICE_MODE_SINGLE, list.getChoiceMode());
            CheckedTextView selected = checkedTextView(list, 0);
            CheckedTextView unselected = checkedTextView(list, 1);
            assertNotNull(selected);
            assertNotNull(unselected);
            assertTrue(selected.isChecked());
            assertFalse(unselected.isChecked());
            assertRadio(selected, true);
            assertRadio(unselected, true);
            assertTrue(selected.getCheckMarkDrawable() == null);
            assertTrue(unselected.getCheckMarkDrawable() == null);
            UiCapture.save(dialog.getWindow().getDecorView(), screenshotPath);

            list.performItemClick(unselected, 1, list.getAdapter().getItemId(1));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(list.isItemChecked(1));
            assertTrue(unselected.isChecked());
            assertEquals("1x", list.getAdapter().getItem(1));
        }
    }

    @Test
    public void darkMultiChoiceUsesOneCheckboxAndKeepsNativeCheckedState() throws Exception {
        assertMultiChoice("dialogs/dark/multi-choice.png");
    }

    @Test
    @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightMultiChoiceUsesOneCheckboxAndKeepsNativeCheckedState() throws Exception {
        assertMultiChoice("dialogs/light/multi-choice.png");
    }

    private void assertMultiChoice(String screenshotPath) throws Exception {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Included diagnostics")
                    .setMultiChoiceItems(new String[]{"Events", "Crashes"}, new boolean[]{true, false}, null)
                    .setNegativeButton("Done", null)
                    .create();
            dialog.show();
            SettingsUi.styleStandardAlertDialog(dialog);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            ListView list = dialog.getListView();
            assertEquals(ListView.CHOICE_MODE_MULTIPLE, list.getChoiceMode());
            CheckedTextView selected = checkedTextView(list, 0);
            CheckedTextView unselected = checkedTextView(list, 1);
            assertNotNull(selected);
            assertNotNull(unselected);
            assertTrue(selected.isChecked());
            assertFalse(unselected.isChecked());
            assertRadio(selected, false);
            assertRadio(unselected, false);
            assertTrue(selected.getCheckMarkDrawable() == null);
            assertTrue(unselected.getCheckMarkDrawable() == null);
            UiCapture.save(dialog.getWindow().getDecorView(), screenshotPath);

            list.performItemClick(unselected, 1, list.getAdapter().getItemId(1));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(list.isItemChecked(1));
            assertTrue(unselected.isChecked());
            assertTrue(dialog.isShowing());
        }
    }

    private static CheckedTextView checkedTextView(ListView list, int position) {
        View row = list.getChildAt(position);
        return findCheckedTextView(row);
    }

    private static CheckedTextView findCheckedTextView(View view) {
        if (view instanceof CheckedTextView) {
            return (CheckedTextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CheckedTextView result = findCheckedTextView(group.getChildAt(i));
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static void assertRadio(CheckedTextView view, boolean expected) throws Exception {
        Drawable[] drawables = view.getCompoundDrawablesRelative();
        Drawable drawable = drawables[0];
        assertNotNull(drawable);
        assertEquals("DialogCheckMarkDrawable", drawable.getClass().getSimpleName());
        Field field = drawable.getClass().getDeclaredField("radio");
        field.setAccessible(true);
        assertEquals(expected, field.getBoolean(drawable));
    }
}
