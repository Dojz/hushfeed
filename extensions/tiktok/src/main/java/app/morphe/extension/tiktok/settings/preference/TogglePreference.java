/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/TogglePreference.java
 */

package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.content.Context;
import android.preference.SwitchPreference;
import android.view.View;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.Utils;

@SuppressWarnings("deprecation")
public class TogglePreference extends SwitchPreference {

    /**
     * The sentence a row gets when its switch does nothing until TikTok is started again.
     *
     * <p>Fifty settings need a restart and thirty-four of their summaries said so, each in its
     * own words, which left sixteen switches that look like they did nothing. A switch that
     * silently does nothing is the hardest kind of bug to report, and two of the "this does not
     * work" reports on the tracker are about restart-gated switches.
     */
    public static final String RESTART_SENTENCE = "Restart TikTok to apply this.";

    /** What an existing summary already says in its own words, so it is not said twice. */
    private static final String RESTART_MARKER = "Restart TikTok";

    public TogglePreference(Context context, String title, String summary, BooleanSetting setting) {
        super(context);
        setTitle(title);
        setSummary(withRestartNote(context, summary, setting));
        setKey(setting.key);
        setChecked(setting.get());
    }

    /**
     * Adds the restart sentence to a summary whose setting needs one and does not have one.
     *
     * <p>The two halves are looked up separately and then joined, because the joined sentence is
     * not a key in the table: translating it as one string would lose the translation of the
     * summary as well. The check is against the English, which is the key, so it cannot be
     * confused by a translation that words the sentence differently.
     */
    private static CharSequence withRestartNote(Context context, String summary,
                                                BooleanSetting setting) {
        if (setting == null || !setting.rebootApp || summary == null) return summary;
        if (summary.contains(RESTART_MARKER)) return summary;
        String translated = L10n.t(context, summary);
        String note = L10n.t(context, RESTART_SENTENCE);
        return summary.isEmpty() ? note : translated + " " + note;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        Utils.setTitleAndSummaryColor(view);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(L10n.t(getContext(), summary));
    }
}
