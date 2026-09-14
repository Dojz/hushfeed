package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import app.morphe.extension.tiktok.settings.L10n;

@SuppressWarnings("deprecation")
public final class MorpheTikTokAboutPreference extends Preference {
    public MorpheTikTokAboutPreference(Context context) {
        super(context);
        setTitle("Hushfeed");
        setSummary(summaryFor(context));
        setOnPreferenceClickListener(preference -> {
            app.morphe.extension.shared.Utils.openLink("https://github.com/SysAdminDoc/hushfeed");
            return true;
        });
    }

    /**
     * The bundle's own version, where somebody filing a bug report can read it off.
     *
     * <p>Nothing in the app said which Hushfeed was installed. The version is stamped into the
     * extension at patch time and only the exported diagnostic report printed it, so every one of
     * the first three bug reports had to be asked for it separately, and the settings search had
     * nothing to find when somebody looked for "version".
     *
     * <p>Empty during tests and in an unpatched build, where the stamp has not been written. The
     * row falls back to what it always said rather than showing an empty version.
     */
    private static CharSequence summaryFor(Context context) {
        String version = app.morphe.extension.shared.Utils.getPatchesReleaseVersion();
        String source = L10n.t(context, "Source code and releases");
        return version == null || version.isEmpty()
                ? source
                : L10n.f(context, "Version %1$s", version) + ". " + source;
    }
}
