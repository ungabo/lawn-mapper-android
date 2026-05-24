package com.gabecodex.lawnmapper;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

final class SnapshotStore {
    static final String ALBUM_NAME = "LawnMapper";

    private SnapshotStore() {
    }

    static Uri saveSnapshot(Context context, Bitmap bitmap) throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "lawnmapper_" + timestamp + ".png";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM_NAME);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore insert failed");
            }
            try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                if (outputStream == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                    throw new IOException("PNG write failed");
                }
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        }

        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM_NAME);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create snapshot directory");
        }
        File output = new File(directory, fileName);
        try (OutputStream outputStream = new FileOutputStream(output)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                throw new IOException("PNG write failed");
            }
        }
        return Uri.fromFile(output);
    }

    static ArrayList<SnapshotItem> loadSnapshots(Context context) {
        ArrayList<SnapshotItem> snapshots = new ArrayList<>();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
        };

        String selection;
        String[] args;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            args = new String[]{Environment.DIRECTORY_PICTURES + "/" + ALBUM_NAME + "%"};
        } else {
            selection = MediaStore.Images.Media.DATA + " LIKE ?";
            args = new String[]{"%" + File.separator + ALBUM_NAME + File.separator + "%"};
        }

        try (Cursor cursor = context.getContentResolver().query(
                collection,
                projection,
                selection,
                args,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
        )) {
            if (cursor == null) {
                return snapshots;
            }
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idIndex);
                Uri uri = ContentUris.withAppendedId(collection, id);
                snapshots.add(new SnapshotItem(uri, cursor.getString(nameIndex), cursor.getLong(dateIndex)));
            }
        } catch (RuntimeException ignored) {
            return snapshots;
        }
        return snapshots;
    }

    static final class SnapshotItem {
        final Uri uri;
        final String name;
        final long dateAddedSeconds;

        SnapshotItem(Uri uri, String name, long dateAddedSeconds) {
            this.uri = uri;
            this.name = name;
            this.dateAddedSeconds = dateAddedSeconds;
        }
    }
}
