/*
 * Copyright (c) 2016, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package util;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import com.android.soundrecorder.R;

import java.io.File;

public class DatabaseUtils {
    private static final String TAG = "DatabaseUtils";
    private static final String PLAY_LIST_NAME = "My recordings";
    public static final String VOLUME_NAME = "external";
    public static final int NOT_FOUND = -1;
    public static final Uri FILE_BASE_URI = Uri.parse("content://media/external/file");

    /*
     * A simple utility to do a query into the databases.
     */
    public static Cursor query(ContentResolver resolver, Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        try {
            if (resolver == null) {
                return null;
            }
            return resolver.query(uri, projection, selection, selectionArgs, sortOrder);
        } catch (UnsupportedOperationException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /*
     * Add the given audioId to the playlist with the given playlistId; and
     * maintain the play_order in the playlist.
     */
    public static void addToPlaylist(ContentResolver resolver, int audioId, long playlistId) {
        String[] cols = new String[] {
            "count(*)"
        };
        Uri uri = MediaStore.Audio.Playlists.Members.getContentUri(VOLUME_NAME, playlistId);
        Cursor cur = query(resolver, uri, cols, null, null, null);
        if (cur != null) {
            cur.moveToFirst();
            final int base = cur.getInt(0);
            cur.close();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, base + audioId);
            values.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, audioId);
            resolver.insert(uri, values);
        }
    }

    /*
     * Obtain the id for the default play list from the audio_playlists table.
     */
    public static int getPlaylistId(ContentResolver resolver) {
        Uri uri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI;
        final String[] ids = new String[] {
            MediaStore.Audio.Playlists._ID
        };
        final String where = MediaStore.Audio.Playlists.NAME + "=?";
        final String[] args = new String[] {
            PLAY_LIST_NAME
        };
        Cursor cursor = query(resolver, uri, ids, where, args, null);
        if (cursor == null) {
            Log.w(TAG, "query returns null");
        }
        int id = NOT_FOUND;
        if (cursor != null) {
            cursor.moveToFirst();
            if (!cursor.isAfterLast()) {
                id = cursor.getInt(0);
            }
            cursor.close();
        }
        return id;
    }

    /*
     * Create a playlist with the given default playlist name, if no such
     * playlist exists.
     */
    public static Uri createPlaylist(Context context, ContentResolver resolver) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Playlists.NAME, PLAY_LIST_NAME);
        Uri uri = resolver.insert(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            new AlertDialog.Builder(context).setTitle(R.string.app_name)
                    .setMessage(R.string.error_mediadb_new_record)
                    .setPositiveButton(R.string.button_ok, null).setCancelable(false).show();
        }
        return uri;
    }

    public static boolean isDataExist(ContentResolver resolver, File file) {
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        final String[] ids = new String[] {
                MediaStore.Audio.Playlists._ID
        };
        final String where = MediaStore.Audio.Playlists.DATA + "=?";
        final String[] args = new String[] {
                file.getAbsolutePath()
        };
        Cursor cursor = query(resolver, uri, ids, where, args, null);

        if (cursor != null && cursor.getCount() > 0) {
            cursor.close();
            return true;
        }
        return false;
    }

    /*
     * Adds file and returns content uri.
     */
    public static Uri addToMediaDB(Context context, File file, long duration, String mimeType) {
        Resources res = context.getResources();
        ContentValues cv = new ContentValues();
        long current = System.currentTimeMillis();
        long modDate = file.lastModified();

        String title = "";
        if (!"".equals(res.getString(R.string.def_save_name_prefix))) {
            title = file.getAbsolutePath().substring(
                    file.getAbsolutePath().lastIndexOf("/") + 1, file.getAbsolutePath().length());
        } else {
            title = FileUtils.getLastFileName(file, false);
        }

        // Label the recorded audio file as MUSIC so that the file
        // will be displayed automatically
        cv.put(MediaStore.Audio.Media.IS_MUSIC, "1");

        cv.put(MediaStore.Audio.Media.TITLE, title);
        cv.put(MediaStore.Audio.Media.DATA, file.getAbsolutePath());
        cv.put(MediaStore.Audio.Media.DATE_ADDED, (int) (current / 1000));
        cv.put(MediaStore.Audio.Media.DATE_MODIFIED, (int) (modDate / 1000));
        cv.put(MediaStore.Audio.Media.DURATION, duration);
        cv.put(MediaStore.Audio.Media.MIME_TYPE, mimeType);
        cv.put(MediaStore.Audio.Media.ARTIST,
                res.getString(R.string.audio_db_artist_name));
        cv.put(MediaStore.Audio.Media.ALBUM,
                res.getString(R.string.audio_db_album_name));
        Log.d(TAG, "Inserting audio record: " + cv.toString());
        ContentResolver resolver = context.getContentResolver();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Log.d(TAG, "ContentURI: " + base);
        Uri result;
        try {
            result = resolver.insert(base, cv);
        } catch (Exception exception) {
            result = null;
        }
        if (result == null) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.error_mediadb_new_record)
                    .setPositiveButton(R.string.button_ok, null)
                    .setCancelable(false)
                    .show();
            return null;
        }
        if (getPlaylistId(resolver) == NOT_FOUND) {
            createPlaylist(context, resolver);
        }
        int audioId = Integer.valueOf(result.getLastPathSegment());
        addToPlaylist(resolver, audioId, getPlaylistId(resolver));

        // Notify those applications such as Music listening to the
        // scanner events that a recorded audio file just created.
        context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, result));
        return result;
    }

    public static void rename(Context context, File file, File newFile) {
        ContentResolver resolver = context.getContentResolver();
        String title = FileUtils.getLastFileName(newFile, false);

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Media.TITLE, title);
        cv.put(MediaStore.Audio.Media.DATA, newFile.getAbsolutePath());

        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String where = MediaStore.Audio.Media.DATA + " = \'" + file.getAbsolutePath() + "\'";
        resolver.update(base, cv, where, null);
    }

    public static void delete(Context context, File file) {
        ContentResolver resolver = context.getContentResolver();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String where = MediaStore.Audio.Media.DATA + " = \'" + file.getAbsolutePath() + "\'";
        resolver.delete(base, where, null);
    }

    public static long getFolderId(ContentResolver resolver, String folderPath) {
        String[] projection = {
                MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA
        };
        String selection = MediaStore.Files.FileColumns.DATA + " = \'" + folderPath + "\'";
        Cursor cursor = DatabaseUtils.query(resolver, FILE_BASE_URI, projection, selection, null,
                MediaStore.Files.FileColumns._ID + " ASC LIMIT 1");
        if (cursor != null) {
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
            if (cursor.getCount() > 0) {
                cursor.moveToNext();
                long id = cursor.getInt(idIndex);
                cursor.close();
                return id;
            }
        }
        return NOT_FOUND;
    }

    public static Cursor getFolderCursor(ContentResolver resolver, long folderId) {
        return getFolderCursor(resolver, new Long[] {folderId});
    }

    public static Cursor getFolderCursor(ContentResolver resolver, Long[] folderIds) {
        if (folderIds == null || folderIds.length == 0) {
            return null;
        }
        String[] projection = {
                MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA,
                MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Files.FileColumns.PARENT
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + "=1" + " AND "
                + MediaStore.Files.FileColumns.PARENT + "=";
        StringBuilder allSelection = new StringBuilder();
        for (int i = 0; i < folderIds.length; i++) {
            if (i != 0) {
                allSelection.append(" OR ");
            }
            allSelection.append("(" + selection + "\'" + folderIds[i] + "\'" + ")");
        }
        return DatabaseUtils.query(resolver, FILE_BASE_URI, projection, allSelection.toString(),
                null, MediaStore.Audio.Media.DATE_MODIFIED + " DESC");
    }

}
