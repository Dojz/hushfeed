/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A short list of creators whose posts get past the preference filters.
 *
 * <p>Somebody who hides photo posts, old posts, long posts or posts with few likes may still
 * want everything a few chosen accounts post. The list names those accounts by handle or by
 * id, and a post of theirs that only a filter in {@link #SUBJECTIVE} would hide stays in the
 * feed. Every filter in {@link #HARD} still hides it: what the reader blocked by name (a
 * creator, a caption word, a sound, a country), ads and commerce, LIVE and the other
 * interruptions, and videos already watched. An exception never reaches those, so listing
 * an account the block list also names changes nothing, and the settings row says so.
 *
 * <p>Entries are exact. A pattern between slashes is refused by the editor and, should one
 * arrive in a backup, matches nobody; so does anything with a space in it, which is a display
 * name rather than a handle. Case and a leading @ are dropped on both sides.
 */
public final class CreatorExceptions {
    /**
     * The filters a chosen creator's post passes: those on a post's kind, its labels, its age,
     * its length and its numbers. A preference about posts in general, not about this one.
     */
    static final Set<Class<? extends IFilter>> SUBJECTIVE = Set.of(
            StoryFilter.class,
            ImageVideoFilter.class,
            LocationBadgeFilter.class,
            ContentMarkerFilters.AiGeneratedFilter.class,
            ContentMarkerFilters.VerifiedFilter.class,
            ContentMarkerFilters.PlaylistFilter.class,
            AdvancedFeedRules.PublicationAgeFilter.class,
            AdvancedFeedRules.QualityFilter.class,
            ViewCountFilter.class,
            LikeCountFilter.class,
            CommentCountFilter.class,
            FavouriteCountFilter.class,
            ShareCountFilter.class);

    /**
     * The filters no exception reaches. Blocks the reader made by name, ads and everything
     * sold (Shop, paid partnerships, promotional music, paid Series and dramas, the cards
     * between videos), LIVE and its replays, and videos already watched.
     */
    static final Set<Class<? extends IFilter>> HARD = Set.of(
            AdsFilter.class,
            LiveFilter.class,
            ShopFilter.class,
            SoundFilter.class,
            ContentMarkerFilters.PaidPartnershipFilter.class,
            ContentMarkerFilters.SeriesFilter.class,
            ContentMarkerFilters.DramaFilter.class,
            CardFilters.InsertedCardFilter.class,
            SeenVideoFilter.class,
            AdvancedFeedRules.KeywordFilter.class,
            AdvancedFeedRules.CreatorFilter.class,
            AdvancedFeedRules.PromotionalMusicFilter.class,
            AdvancedFeedRules.LiveReplayFilter.class,
            RegionFilter.class);

    /** One list's exact names, kept while the list reads the same. */
    private static final class Names {
        final String source;
        final Set<String> exact;

        Names(String source, Set<String> exact) {
            this.source = source;
            this.exact = exact;
        }
    }

    private static final Object LOCK = new Object();
    private static volatile Names current;
    private static final AtomicInteger failureLogCount = new AtomicInteger();

    private CreatorExceptions() {
    }

    /** Whether a match from {@code filter} is one an excepted creator's post gets past. */
    static boolean isSubjective(IFilter filter) {
        return SUBJECTIVE.contains(filter.getClass());
    }

    /** Whether the creator of {@code item} is on the list. Nobody is, on any failure. */
    static boolean excepted(Aweme item) {
        try {
            Set<String> names = names(Settings.CREATOR_FILTER_EXCEPTIONS.get());
            if (names.isEmpty()) return false;
            return CreatorIdentity.of(item).namedIn(names);
        } catch (RuntimeException failure) {
            // The filter that matched keeps its say. Said three times, then counted quietly.
            if (failureLogCount.getAndIncrement() < 3) {
                Logger.printException(() -> "Could not read who posted a feed item for the"
                        + " creator exceptions; the filter stands", failure);
            }
            return false;
        }
    }

    /**
     * A token for the filter mask, so a list that changed rescans a page the filter has seen.
     * Empty while there are no exceptions, which keeps every mask as it was.
     */
    static String maskToken() {
        Set<String> names = names(Settings.CREATOR_FILTER_EXCEPTIONS.get());
        return names.isEmpty() ? "" : "CreatorExceptions#" + Integer.toHexString(names.hashCode());
    }

    /** The exact, normalized names in {@code source}. Patterns and display names are left out. */
    static Set<String> names(String source) {
        Names found = current;
        if (found != null && found.source.equals(source)) return found.exact;
        synchronized (LOCK) {
            found = current;
            if (found == null || !found.source.equals(source)) {
                found = new Names(source, parse(source));
                current = found;
            }
            return found.exact;
        }
    }

    private static Set<String> parse(String source) {
        Set<String> exact = new LinkedHashSet<>();
        for (String entry : AdvancedFeedRules.rawTerms(source)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || AdvancedFeedRules.isPattern(trimmed) || hasSpace(trimmed)) continue;
            String normalized = AdvancedFeedRules.normalizedCreator(trimmed);
            if (!normalized.isEmpty()) exact.add(normalized);
        }
        return exact.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(exact);
    }

    private static boolean hasSpace(String entry) {
        for (int index = 0; index < entry.length(); index++) {
            if (Character.isWhitespace(entry.charAt(index))) return true;
        }
        return false;
    }

    /**
     * What is wrong with the list as typed, or null when nothing is. Said while the editor is
     * still open, because an entry the list cannot use would otherwise sit there looking like
     * it works.
     */
    @Nullable
    public static String entryProblem(String list) {
        if (list == null) return null;
        String limitProblem = FeedRuleLimits.creatorProblem(list);
        if (limitProblem != null) return limitProblem;
        for (String entry : AdvancedFeedRules.rawTerms(list)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            if (AdvancedFeedRules.isPattern(trimmed)) {
                return L10n.f("An exception names one creator, so a pattern can't be one: %1$s",
                        L10n.isolate(trimmed));
            }
            if (hasSpace(trimmed)) {
                return L10n.f("An exception takes a handle or id, not a display name: %1$s",
                        L10n.isolate(trimmed));
            }
        }
        return null;
    }

    /**
     * The entries of {@code list} that a block list also names, in the order they were typed.
     * Those creators stay hidden whatever the exception says, and the row says so rather than
     * leaving the reader to work out why the exception does nothing.
     */
    public static List<String> alsoBlocked(String list) {
        List<String> found = new ArrayList<>();
        for (String entry : AdvancedFeedRules.creatorEntries(list)) {
            if (AdvancedFeedRules.isPattern(entry) || hasSpace(entry)) continue;
            if (AdvancedFeedRules.blockListsName(entry)) found.add(entry);
        }
        return found;
    }

    /** The line under the settings row naming the conflicts, or null when there are none. */
    @Nullable
    public static String conflictNote(String list) {
        List<String> names = alsoBlocked(list);
        if (names.isEmpty()) return null;
        String joined = String.join(", ", names);
        return L10n.f("Also on a block list, so still hidden: %1$s", L10n.isolate(joined));
    }
}
