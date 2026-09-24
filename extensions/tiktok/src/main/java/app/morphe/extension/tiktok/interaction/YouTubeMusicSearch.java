/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.feedfilter.SoundIdentity;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Hands the current video's sound to YouTube Music as a search.
 *
 * <p>TikTok once offered to save a sound to a YouTube Music playlist and took the option away.
 * YouTube Music has no API a phone can add to a playlist with short of an account token, and
 * Hushfeed holds no account of anyone's, so this stops at the search: the sound's title and
 * artist, as TikTok's own sound page shows them, opened in YouTube Music's search box. What the
 * reader does there is theirs. Nothing is sent anywhere else, and nothing is fetched first: the
 * title and artist are already in the video model, so the gesture answers at once.
 */
public final class YouTubeMusicSearch {
    static final String PACKAGE = "com.google.android.apps.youtube.music";
    static final String SEARCH_URL = "https://music.youtube.com/search?q=";

    private YouTubeMusicSearch() {
    }

    /** Opens YouTube Music's search for the sound of {@code aweme}. Says why when it cannot. */
    public static boolean open(Object aweme, Context context) {
        if (context == null) return false;
        String query = query(aweme);
        if (query == null) {
            Utils.showToastShort(L10n.t("This video's sound has no title to look for"));
            return false;
        }
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(SEARCH_URL + Uri.encode(query)));
        view.setPackage(PACKAGE);
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(view);
            return true;
        } catch (ActivityNotFoundException notInstalled) {
            Utils.showToastShort(L10n.t("YouTube Music isn't installed"));
            return false;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not open YouTube Music", failure);
            Utils.showToastLong(L10n.t("YouTube Music couldn't be opened. Open it yourself and search for the sound."));
            return false;
        }
    }

    /** "title artist", or the title alone, or null when the sound has no title to search by. */
    static String query(Object aweme) {
        Object music = aweme == null ? null : Reflect.property(aweme, "getMusic", "music");
        if (music == null) return null;
        String title = SoundIdentity.nameOf(music);
        if (title == null) return null;
        String artist = SoundIdentity.authorOf(music);
        return artist == null ? title : title + " " + artist;
    }
}
