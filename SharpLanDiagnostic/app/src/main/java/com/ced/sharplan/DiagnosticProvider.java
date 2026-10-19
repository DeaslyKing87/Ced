package com.ced.sharplan;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Shares only explicitly generated read-only ZIP snapshots via URI grants. */
public class DiagnosticProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null
                || !(getContext().getPackageName() + ".diagnostics").equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1
                || !uri.getLastPathSegment().matches("report-[a-f0-9-]{36}\\.zip"))
            throw new FileNotFoundException("Invalid diagnostic export.");
        File file = new File(getContext().getCacheDir(), uri.getLastPathSegment());
        if (!file.isFile()) throw new FileNotFoundException("Diagnostic export expired.");
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only diagnostic export.");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/zip"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] args, String sortOrder) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++)
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "SharpLanDiagnostic-logs.zip";
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            cursor.addRow(values);
            return cursor;
        } catch (FileNotFoundException e) { return null; }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
}
