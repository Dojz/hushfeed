package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.*;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Hide mini dramas (upstream #155). A drama episode read on the S22 on 2026-09-23, in TikTok's
 * series viewer after a search for short dramas, carried isPaidContent, a paid collection id and
 * name, episode 1, is_limited_free and 1,182 characters of mini_drama_info, and no drama card.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MiniDramaFilterTest {
    private final IFilter drama = new ContentMarkerFilters.DramaFilter();
    private final IFilter series = new ContentMarkerFilters.SeriesFilter();

    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.HIDE_MINI_DRAMAS.save(false);
        Settings.HIDE_SERIES.save(false);
    }

    @After public void tearDown() {
        Settings.HIDE_MINI_DRAMAS.resetToDefault();
        Settings.HIDE_SERIES.resetToDefault();
    }

    @Test public void theSwitchIsOffByDefaultAndIndependentOfHideSeries() {
        Settings.HIDE_MINI_DRAMAS.resetToDefault();
        assertFalse(Settings.HIDE_MINI_DRAMAS.get());
        Settings.HIDE_SERIES.save(true);
        assertFalse(drama.getEnabled());
        Settings.HIDE_MINI_DRAMAS.save(true);
        Settings.HIDE_SERIES.save(false);
        assertTrue(drama.getEnabled());
        assertFalse(series.getEnabled());
    }

    @Test public void theEpisodeReadOnThePhoneIsADramaAndASeries() {
        Item episode = new Item();
        episode.isPaidContent = true;
        PaidContent info = new PaidContent();
        info.paidCollectionId = 7675627111381472277L;
        info.collectionName = "x".repeat(56);
        info.episodeNumber = "1";
        info.isLimitedFreeShortDrama = true;
        info.miniDramaInfo = "{" + "x".repeat(1180) + "}";
        episode.mPaidContentInfo = info;
        assertTrue(drama.getFiltered(episode));
        assertTrue("a drama episode is a paid Series item too", series.getFiltered(episode));
    }

    /** PaidContentInfo rides along on ordinary videos with its fields at their defaults. */
    @Test public void theDefaultsTikTokSendsOnOrdinaryVideosAreNotADrama() {
        assertFalse(drama.getFiltered(null));
        assertFalse(drama.getFiltered(new Aweme()));
        Item ordinary = new Item();
        assertFalse(drama.getFiltered(ordinary));
        ordinary.mPaidContentInfo = new PaidContent();
        assertFalse("an empty paid content struct", drama.getFiltered(ordinary));
        PaidContent blank = new PaidContent();
        blank.miniDramaInfo = "   ";
        blank.miniDramaCardInfo = new Card(" ", List.of());
        ordinary.mPaidContentInfo = blank;
        assertFalse("blank drama text and an empty card", drama.getFiltered(ordinary));
        PaidContent limitedOnly = new PaidContent();
        limitedOnly.isLimitedFreeShortDrama = true;
        ordinary.mPaidContentInfo = limitedOnly;
        assertFalse("is_limited_free alone may be any paid series' free window", drama.getFiltered(ordinary));
    }

    @Test public void eachDramaSignalMatchesOnItsOwn() {
        Item item = new Item();
        PaidContent text = new PaidContent();
        text.miniDramaInfo = "{\"series_video_type\":\"drama\"}";
        item.mPaidContentInfo = text;
        assertTrue("drama text", drama.getFiltered(item));

        PaidContent typedCard = new PaidContent();
        typedCard.miniDramaCardInfo = new Card("drama_rec", List.of());
        item.mPaidContentInfo = typedCard;
        assertTrue("a card with a type", drama.getFiltered(item));

        PaidContent fullCard = new PaidContent();
        fullCard.miniDramaCardInfo = new Card(null, List.of(new Object(), new Object()));
        item.mPaidContentInfo = fullCard;
        assertTrue("a card holding dramas", drama.getFiltered(item));
        assertFalse("a card alone is not a paid Series item", series.getFiltered(item));
    }

    private static final class Item extends Aweme {
        public boolean isPaidContent;
        public Object mPaidContentInfo;
    }

    /** TikTok's PaidContentInfo, by the fields the Series and drama filters read. */
    private static final class PaidContent {
        long paidCollectionId;
        String collectionName;
        String episodeNumber;
        boolean isPaidCollectionIntro;
        boolean isLimitedFreeShortDrama;
        String miniDramaInfo;
        Object miniDramaCardInfo;
    }

    /** TikTok's MiniDramaCardInfo, by its card type and the dramas it promotes. */
    private static final class Card {
        final String cardType;
        final List<Object> dramas;

        Card(String cardType, List<Object> dramas) {
            this.cardType = cardType;
            this.dramas = dramas;
        }
    }
}
