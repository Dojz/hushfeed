/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * What an interrupted publish leaves behind, and whether the next run finds it.
 *
 * <p>The window is between MediaStore handing back a row and the journal holding that row's URI.
 * A crash there leaves a pending row the gallery will never show and nothing will ever delete,
 * and all recovery has to go on is the name the insert asked for. The old scan returned on the
 * first row it found published under that name, which is a row an earlier save left, so a
 * gallery that already held video.mp4 kept every orphan forever. Rows come back in whatever
 * order the provider chose, so the two orders are separate cases.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class MediaCacheRecoveryTest {
    private static final Uri COLLECTION = Uri.parse("content://media/external_primary/downloads");
    private static final String FOLDER = "Download/Hushfeed";

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeMediaProvider.reset();
        Robolectric.setupContentProvider(FakeMediaProvider.class, "media");
    }

    @After
    public void tearDown() throws Exception {
        FakeMediaProvider.reset();
        File journal = journal();
        if (journal.exists()) assertTrue(journal.delete());
    }

    @Test
    public void aPendingRowGoesWhenAPublishedOneWithTheSameNameComesFirst() throws Exception {
        FakeMediaProvider.add(1L, "video.mp4", 0, FOLDER);
        FakeMediaProvider.add(2L, "video.mp4", 1, FOLDER);

        reconcileStaleToken("video.mp4", FOLDER);

        assertEquals("the published row was deleted", List.of(1L), FakeMediaProvider.ids());
        assertEquals("the orphan was left behind", List.of(2L), FakeMediaProvider.deletedIds());
        assertJournalIsEmpty();
    }

    @Test
    public void aPendingRowGoesWhenItComesFirst() throws Exception {
        FakeMediaProvider.add(2L, "video.mp4", 1, FOLDER);
        FakeMediaProvider.add(1L, "video.mp4", 0, FOLDER);

        reconcileStaleToken("video.mp4", FOLDER);

        assertEquals(List.of(1L), FakeMediaProvider.ids());
        assertEquals(List.of(2L), FakeMediaProvider.deletedIds());
        assertJournalIsEmpty();
    }

    @Test
    public void aLegacyTokenTakesEveryPendingRowUnderTheName() throws Exception {
        // A token from before the folder was recorded cannot scope itself, so it takes every
        // folder. A pending row is visible only to the app that owns it from API 29, so there is
        // nobody else's here to delete.
        FakeMediaProvider.add(1L, "video.mp4", 1, "Download/Hushfeed");
        FakeMediaProvider.add(2L, "video.mp4", 1, "Movies/Hushfeed");
        FakeMediaProvider.add(3L, "video.mp4", 0, "Download/Hushfeed");
        FakeMediaProvider.add(4L, "other.mp4", 1, "Download/Hushfeed");

        reconcileStale(legacyToken("video.mp4"));

        assertEquals(List.of(3L, 4L), FakeMediaProvider.ids());
        assertJournalIsEmpty();
    }

    @Test
    public void aTokenThatNamesAFolderLeavesTheOtherFoldersAlone() throws Exception {
        FakeMediaProvider.add(1L, "video.mp4", 1, "Download/Hushfeed/");
        FakeMediaProvider.add(2L, "video.mp4", 1, "Movies/Hushfeed/");

        reconcileStaleToken("video.mp4", FOLDER);

        // The trailing separator is the provider's and the caller writes none, and the two are
        // the same folder.
        assertEquals(List.of(2L), FakeMediaProvider.ids());
        assertJournalIsEmpty();
    }

    @Test
    public void aDeletionThatFailsKeepsTheEntryForTheNextRun() throws Exception {
        FakeMediaProvider.add(1L, "video.mp4", 1, FOLDER);
        FakeMediaProvider.refuseDeletes = true;

        String token = reconcileStaleToken("video.mp4", FOLDER);

        assertEquals("the row is still there", List.of(1L), FakeMediaProvider.ids());
        assertTrue("the journal forgot a row it never cleaned", journalText().contains(token));
    }

    @Test
    public void anEntryWithNoRowLeftIsDropped() throws Exception {
        reconcileStaleToken("video.mp4", FOLDER);

        assertEquals(List.of(), FakeMediaProvider.deletedIds());
        assertJournalIsEmpty();
    }

    /**
     * Without this every case above would pass against a recovery that deleted every pending row
     * it could see, which would take a download being written right now.
     */
    @Test
    public void aRowUnderAnotherNameIsNotTouched() throws Exception {
        FakeMediaProvider.add(1L, "someone-elses.mp4", 1, FOLDER);

        reconcileStaleToken("video.mp4", FOLDER);

        assertEquals(List.of(1L), FakeMediaProvider.ids());
        assertJournalIsEmpty();
    }

    /** Writes a token, ages it past the stale window, and runs one reconciliation. */
    private String reconcileStaleToken(String displayName, String folder) throws Exception {
        return reconcileStale(MediaCache.beginPending(context, COLLECTION, displayName, folder));
    }

    private String reconcileStale(String token) throws Exception {
        writeJournal(token, System.currentTimeMillis() - MediaCache.STALE_AFTER_MS - 60_000L);
        MediaCache.reconcile(context);
        return token;
    }

    /** A token from before the folder was recorded: three fields rather than four. */
    private String legacyToken(String displayName) {
        return "intent:0:" + base64(COLLECTION.toString()) + ":" + base64(displayName);
    }

    private static String base64(String value) {
        return Base64.encodeToString(
                value.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP);
    }

    private File journal() {
        return new File(
                new File(context.getCacheDir(), MediaCache.DIRECTORY_NAME), "pending-uris.tsv");
    }

    private void writeJournal(String token, long timestamp) throws Exception {
        File file = journal();
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        String line = timestamp + "\t" + token + "\n";
        Files.write(file.toPath(), line.getBytes(StandardCharsets.UTF_8));
    }

    private String journalText() throws Exception {
        File file = journal();
        if (!file.exists()) return "";
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private void assertJournalIsEmpty() throws Exception {
        assertFalse("the journal still holds an entry: " + journalText(),
                journalText().contains("intent:"));
    }

    /** Just enough MediaStore to answer a name query and delete a row by id. */
    public static final class FakeMediaProvider extends ContentProvider {
        private static final List<Object[]> ROWS = new ArrayList<>();
        private static final List<Long> DELETED = new ArrayList<>();
        static boolean refuseDeletes;

        static void reset() {
            ROWS.clear();
            DELETED.clear();
            refuseDeletes = false;
        }

        /** id, display name, is_pending, relative path, in the order the provider returns them. */
        static void add(long id, String name, int pending, String path) {
            ROWS.add(new Object[]{id, name, pending, path});
        }

        static List<Long> ids() {
            List<Long> ids = new ArrayList<>();
            for (Object[] row : ROWS) ids.add((Long) row[0]);
            return ids;
        }

        static List<Long> deletedIds() {
            return new ArrayList<>(DELETED);
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(
                Uri uri, String[] projection, String selection, String[] args, String sort) {
            String wanted = args == null || args.length == 0 ? null : args[0];
            MatrixCursor cursor = new MatrixCursor(projection);
            for (Object[] row : ROWS) {
                if (wanted != null && !wanted.equals(row[1])) continue;
                Object[] values = new Object[projection.length];
                for (int column = 0; column < projection.length; column++) {
                    switch (projection[column]) {
                        case MediaStore.MediaColumns._ID:
                            values[column] = row[0];
                            break;
                        case MediaStore.MediaColumns.DISPLAY_NAME:
                            values[column] = row[1];
                            break;
                        case MediaStore.MediaColumns.IS_PENDING:
                            values[column] = row[2];
                            break;
                        case MediaStore.MediaColumns.RELATIVE_PATH:
                            values[column] = row[3];
                            break;
                        default:
                            values[column] = null;
                            break;
                    }
                }
                cursor.addRow(values);
            }
            return cursor;
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            if (refuseDeletes) return 0;
            long id = ContentUris.parseId(uri);
            for (int at = 0; at < ROWS.size(); at++) {
                if ((Long) ROWS.get(at)[0] == id) {
                    ROWS.remove(at);
                    DELETED.add(id);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }
    }
}
