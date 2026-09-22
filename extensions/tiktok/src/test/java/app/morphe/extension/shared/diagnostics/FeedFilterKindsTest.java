/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/** The element kinds a route counts for the export, next to its list and removal counts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeedFilterKindsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before @After public void startClean() {
        FeedFilterCounters.clear();
    }

    private static String lineFor(String source) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(source + ":")) return line;
        }
        return null;
    }

    @Test public void aRouteNamesTheKindsItWasHandedMostFirstAndCapsThem() {
        FeedFilterCounters.sawList("Route", 20);
        for (int i = 0; i < FeedFilterCounters.MAX_KINDS; i++) {
            FeedFilterCounters.sawKind("Route", "kind " + (char) ('a' + i));
        }
        FeedFilterCounters.sawKind("Route", "kind a");
        FeedFilterCounters.sawKind("Route", "one too many");
        FeedFilterCounters.sawKind("Route", "two too many");
        FeedFilterCounters.sawKind("Route", null);

        String line = lineFor("Route");
        assertTrue(line, line.startsWith("Route: 1 lists, 20 items, 0 removed. Kinds: kind a 2, "
                + FeedFilterCounters.OTHER_KINDS + " 2, kind b 1, kind c 1, "));
        assertFalse("a kind past the cap was named", line.contains("too many"));
    }

    @Test public void kindsGoWithAClearAndComeBackWithItsUndo() {
        FeedFilterCounters.sawList("Route", 1);
        FeedFilterCounters.sawKind("Route", "type 96 product");
        FeedFilterCounters.Snapshot cleared = FeedFilterCounters.snapshotAndClear();
        assertEquals(List.of(), FeedFilterCounters.report());

        // Counted between the clear and the undo, and kept by it.
        FeedFilterCounters.sawList("Route", 1);
        FeedFilterCounters.sawKind("Route", "type 96 product");
        FeedFilterCounters.sawKind("Route", "type 1 video");
        FeedFilterCounters.restore(cleared);

        assertEquals("Route: 2 lists, 2 items, 0 removed. Kinds: type 96 product 2, type 1 video 1", lineFor("Route"));
    }

    @Test public void aRouteThatNamesNoKindsKeepsItsLine() {
        FeedFilterCounters.sawList("Plain", 3);
        FeedFilterCounters.removed("Plain", 1, "AdsFilter");
        assertEquals("Plain: 1 lists, 3 items, 1 removed. Last reason: AdsFilter", lineFor("Plain"));
    }
}
