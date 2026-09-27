package org.freedesktop.monado.compatibility_layer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.io.File;
import java.util.List;

/**
 * The Compatibility-Layer system runtime broker, built into Compatibility-Layer Runtime itself.
 *
 * The Khronos Compatibility-Layer loader (Android XR games, Pico games, current Quest games) asks the provider
 * "org.khronos.compatibility_layer.system_runtime_broker" which runtime to load, and its own manifest already
 * declares that it will query it. Answering from the runtime's package makes that package visible
 * to the game, so it can load libcompatibility_layer_monado.so and bind the runtime service — with no patch.
 *
 * Paths the loader asks for:
 *   compatibility-layer/{major}/abi/{abi}/runtimes/active/0            → the active runtime
 *   compatibility-layer/{major}/abi/{abi}/runtimes/{package}/functions → functions to call by name (none)
 */
public final class RuntimeBroker extends ContentProvider {
    private static final String LIBRARY = "libcompatibility_layer_monado.so";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) {
        List<String> path = uri.getPathSegments();
        if (path.size() < 6 || !"compatibility-layer".equals(path.get(0)) || !"runtimes".equals(path.get(4))) return null;
        String abi = path.get(3);
        if (path.size() >= 7 && "functions".equals(path.get(6))) {
            return new MatrixCursor(new String[]{"function_name", "symbol_name"});
        }
        if (!"active".equals(path.get(5))) return null;
        String libraries = libraryDir(abi);
        MatrixCursor cursor = new MatrixCursor(new String[]{"package_name", "native_lib_dir", "so_filename", "has_functions"});
        if (libraries != null) cursor.addRow(new Object[]{getContext().getPackageName(), libraries, LIBRARY, 0});
        return cursor;
    }

    /** The runtime's library folder for the game's ABI (a 32-bit game needs the 32-bit build). */
    private String libraryDir(String abi) {
        ApplicationInfo info = getContext().getApplicationInfo();
        File primary = new File(info.nativeLibraryDir);
        // The Khronos loader names 32-bit ARM "armeabi-v7a" or "armeabi-v7l", depending on its version.
        String folder = abi.startsWith("arm64") ? "arm64" : abi.startsWith("armeabi") ? "arm" : null;
        if (folder == null) return null;
        if (folder.equals(primary.getName()) && new File(primary, LIBRARY).exists()) return primary.getPath();
        File other = new File(primary.getParentFile(), folder);
        return new File(other, LIBRARY).exists() ? other.getPath() : null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] arguments) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] arguments) {
        return 0;
    }
}
