package org.freedesktop.monado.compatibility_layer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Starts a headset game as it came from the store, without patching it.
 *
 * Such a game targets Android 11+ and does not list the Compatibility-Layer broker in its <queries>, so
 * package visibility hides Compatibility-Layer Runtime from it and its Compatibility-Layer loader finds no runtime. Android
 * makes a package visible to an app that it granted a content URI to: the game is started from
 * here with read access to a URI of this package's {@link VisibilityProvider}, and from then on it
 * sees Compatibility-Layer Runtime — the broker, the library and the service.
 *
 * Compatibility-Layer sends: component = the game's activity ("package/class"), vrapi = true for VrApi games.
 */
public final class GameLauncher extends Activity {
    public static final String EXTRA_COMPONENT = "component";
    /** The game draws through VrApi: it goes on through Compatibility-Layer VrApi Driver. */
    public static final String EXTRA_VRAPI = "vrapi";
    private static final String DRIVER = "com.oculus.systemdriver";
    private static final String DRIVER_LAUNCHER = "dev.compatibility_layer.vrapidriver.GameLauncher";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String name = getIntent().getStringExtra(EXTRA_COMPONENT);
        ComponentName component = name == null ? null : ComponentName.unflattenFromString(name);
        if (component != null) {
            Uri uri = Uri.parse("content://" + VisibilityProvider.AUTHORITY + "/" + component.getPackageName());
            Intent game = new Intent(Intent.ACTION_MAIN)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // A VrApi game also has to see the VrApi driver: the driver's launcher starts it then,
            // passing this package's URI on together with its own.
            if (getIntent().getBooleanExtra(EXTRA_VRAPI, false)) {
                game = new Intent()
                    .setClassName(DRIVER, DRIVER_LAUNCHER)
                    .putExtra(EXTRA_COMPONENT, name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            game.setClipData(ClipData.newRawUri("Compatibility-Layer", uri));
            try {
                startActivity(game);
            } catch (RuntimeException ignored) {
                // Not installed or not startable: nothing to do.
            }
        }
        finish();
    }
}
