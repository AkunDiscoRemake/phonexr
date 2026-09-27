package org.freedesktop.monado.compatibility_layer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * An empty provider whose URIs are granted to games started by {@link GameLauncher}: holding the
 * grant is what makes Compatibility-Layer Runtime visible to them. Not exported, so a grant is really needed.
 */
public final class VisibilityProvider extends ContentProvider {
    public static final String AUTHORITY = "org.freedesktop.monado.compatibility_layer.visibility";

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] arguments) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { return 0; }
}
