package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BlockAuthorLocalizationTest {
    private static final VideoAuthor AUTHOR = new VideoAuthor(
            "raw_uid_23", "raw_sec_uid_23", "raw_creator_23", "raw_aweme_23");

    @Before public void resetState() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        CurrentVideoAuthor.resetForTests();
        ShadowToast.reset();
    }

    @After public void clearActivity() throws Exception {
        java.lang.reflect.Method dismiss =
                BlockAuthorOverlay.class.getDeclaredMethod("dismissUndo");
        dismiss.setAccessible(true);
        dismiss.invoke(null);
        Utils.setActivity(null);
        ShadowToast.reset();
    }

    @Test @Config(sdk = 28, qualifiers = "de")
    public void germanOwnsEveryBlockAndUnblockResult() {
        verify(new Expected(
                "raw_creator_23 blockiert",
                "TikTok hat die Anfrage zum Blockieren von raw_creator_23 abgelehnt",
                "Die Blockierung von raw_creator_23 konnte nicht bestätigt werden",
                "raw_creator_23 entblockt",
                "TikTok hat die Anfrage zum Entblocken von raw_creator_23 abgelehnt",
                "Die Aufhebung der Blockierung von raw_creator_23 konnte nicht bestätigt werden"
        ));
    }

    @Test @Config(sdk = 28, qualifiers = "in-rID")
    public void indonesianOwnsEveryBlockAndUnblockResult() {
        verify(new Expected(
                "raw_creator_23 diblokir",
                "TikTok menolak permintaan untuk memblokir raw_creator_23",
                "Pemblokiran raw_creator_23 tidak dapat dikonfirmasi",
                "raw_creator_23 dibuka blokirnya",
                "TikTok menolak permintaan untuk membuka blokir raw_creator_23",
                "Pembukaan blokir raw_creator_23 tidak dapat dikonfirmasi"
        ));
    }

    private static void verify(Expected expected) {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Utils.setActivity(activity);

            BlockAuthorOverlay.reportBlockResult(
                    AUTHOR, BlockAuthorService.Result.CONFIRMED);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(texts(activity.findViewById(android.R.id.content)).toString(),
                    texts(activity.findViewById(android.R.id.content))
                            .contains(expected.blockConfirmed));

            ShadowToast.reset();
            BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.REJECTED);
            assertEquals(expected.blockRejected, ShadowToast.getTextOfLatestToast());

            ShadowToast.reset();
            BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.UNCONFIRMED);
            assertEquals(expected.blockUnconfirmed, ShadowToast.getTextOfLatestToast());

            ShadowToast.reset();
            BlockAuthorOverlay.reportUnblockResult(AUTHOR, BlockAuthorService.Result.CONFIRMED);
            assertEquals(expected.unblockConfirmed, ShadowToast.getTextOfLatestToast());

            ShadowToast.reset();
            BlockAuthorOverlay.reportUnblockResult(AUTHOR, BlockAuthorService.Result.REJECTED);
            assertEquals(expected.unblockRejected, ShadowToast.getTextOfLatestToast());

            ShadowToast.reset();
            BlockAuthorOverlay.reportUnblockResult(
                    AUTHOR, BlockAuthorService.Result.UNCONFIRMED);
            assertEquals(expected.unblockUnconfirmed, ShadowToast.getTextOfLatestToast());
        }
    }

    private static List<String> texts(View root) {
        List<String> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(View view, List<String> result) {
        if (view instanceof TextView) result.add(((TextView) view).getText().toString());
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            collect(group.getChildAt(index), result);
        }
    }

    private static final class Expected {
        final String blockConfirmed;
        final String blockRejected;
        final String blockUnconfirmed;
        final String unblockConfirmed;
        final String unblockRejected;
        final String unblockUnconfirmed;

        Expected(
                String blockConfirmed,
                String blockRejected,
                String blockUnconfirmed,
                String unblockConfirmed,
                String unblockRejected,
                String unblockUnconfirmed
        ) {
            this.blockConfirmed = blockConfirmed;
            this.blockRejected = blockRejected;
            this.blockUnconfirmed = blockUnconfirmed;
            this.unblockConfirmed = unblockConfirmed;
            this.unblockRejected = unblockRejected;
            this.unblockUnconfirmed = unblockUnconfirmed;
        }
    }
}
