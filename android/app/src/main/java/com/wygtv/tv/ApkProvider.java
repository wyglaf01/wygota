package com.wygtv.tv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Shares the downloaded update (and nothing else) with the system installer, read-only. */
public final class ApkProvider extends ContentProvider {
    static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".update"; // the beta and the release each have their own

    static File file(Context c) { return new File(new File(c.getCacheDir(), "update"), "wygtv.apk"); }

    static Uri uri(Context c) { return Uri.parse("content://" + AUTHORITY + "/wygtv.apk"); }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"wygtv.apk".equals(uri.getLastPathSegment()) || !"r".equals(mode)) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{"wygtv.apk", file(getContext()).length()});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
