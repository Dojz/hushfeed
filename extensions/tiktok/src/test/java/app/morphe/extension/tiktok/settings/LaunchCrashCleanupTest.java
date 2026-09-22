/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.diagnostics.JavaCrashCapture;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Hushfeed's preferences files on the keep-list of TikTok's launch-crash cleanup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LaunchCrashCleanupTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok 47.0.3's own list, `X.03JS.LIZIZ`. */
    private static final String[] TIKTOK_KEEPS = {
            "safe_mode_config_sp", "safe_mode_exception_repo", "safe_mode_v2_status",
            "cold_boot_launch_protector", "launch_crash_intercept_sp", "aweme_user",
            "token_shared_preference", "sp_TicketGuardHelper", "com.bytedance.sdk.account_setting",
            "key_language_sp_key"};

    /** A call that opens preferences by name: TikTok's cleanup deletes that file unless it's kept. */
    private static final Pattern OPENS_BY_NAME =
            Pattern.compile("getSharedPreferences\\(\\s*[^)\\s]|new SharedPrefCategory\\(");

    @Test public void tiktoksListKeepsItsOrderAndGainsHushfeedsFiles() {
        String[] kept = LaunchCrashCleanup.keepHushfeedFiles(TIKTOK_KEEPS.clone());
        assertArrayEquals("TikTok's own names moved or went", TIKTOK_KEEPS,
                Arrays.copyOf(kept, TIKTOK_KEEPS.length));
        assertEquals(Arrays.asList("morphe_prefs", "morphe_feature_gate_lab", "hushfeed-calm-feed-preset"),
                Arrays.asList(kept).subList(TIKTOK_KEEPS.length, kept.length));
        assertArrayEquals(LaunchCrashCleanup.HUSHFEED_FILES, LaunchCrashCleanup.keepHushfeedFiles(null));
    }

    @Test public void everyPreferencesFileHushfeedOpensIsKept() throws Exception {
        assertEquals("a file opens preferences that TikTok's launch-crash cleanup would delete; "
                        + "add its name to LaunchCrashCleanup.HUSHFEED_FILES and to this list",
                new TreeSet<>(Arrays.asList(
                        "diagnostics/JavaCrashCapture.java",
                        "featuregatelab/FeatureGateLabStore.java",
                        "settings/CalmFeedPreset.java",
                        "shared/settings/Setting.java",
                        "shared/settings/preference/SharedPrefCategory.java")),
                openersByName());

        List<String> kept = Arrays.asList(LaunchCrashCleanup.HUSHFEED_FILES);
        assertTrue(kept.contains(Setting.preferences.name));
        assertTrue(kept.contains(constant(JavaCrashCapture.class, "PREFS_NAME")));
        assertTrue(kept.contains(FeatureGateLabStore.PREFS_NAME));
        assertTrue(kept.contains(CalmFeedPreset.PREFERENCES));
    }

    private static String constant(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static Set<String> openersByName() throws IOException {
        Set<String> openers = new TreeSet<>();
        for (Path source : payloadSources()) {
            String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            if (OPENS_BY_NAME.matcher(text).find()) openers.add(relativeName(source));
        }
        return openers;
    }

    private static String relativeName(Path source) {
        String name = source.toString().replace('\\', '/');
        int at = name.indexOf("/app/morphe/extension/tiktok/");
        if (at >= 0) return name.substring(at + "/app/morphe/extension/tiktok/".length());
        at = name.indexOf("/app/morphe/extension/shared/");
        if (at >= 0) return "shared/" + name.substring(at + "/app/morphe/extension/shared/".length());
        return name;
    }

    /** Both trees whose Java ends up in the payload TikTok runs. */
    private static List<Path> payloadSources() throws IOException {
        List<Path> roots = new ArrayList<>();
        for (String candidate : new String[]{
                "src/main/java",
                "extensions/tiktok/src/main/java",
                "../shared/library/src/main/java",
                "extensions/shared/library/src/main/java"}) {
            File directory = new File(candidate);
            if (directory.isDirectory()) roots.add(directory.toPath());
        }
        assertTrue("no payload source tree was found from " + new File(".").getAbsolutePath(),
                roots.size() >= 2);
        List<Path> sources = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                sources.addAll(walk.filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .collect(Collectors.toList()));
            }
        }
        return sources;
    }
}
