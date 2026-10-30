package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.net.Uri;

/** Module assets with the native app's identity, storage and services. Never starts a module Application. */
final class NativeModuleResources {
    static Context packageContext(Context host, Uri media) throws PackageManager.NameNotFoundException {
        String authority = media.getAuthority();
        String suffix = ".fileprovider";
        if (authority == null || !authority.endsWith(suffix) || authority.length() == suffix.length())
            throw new IllegalArgumentException("Unknown module provider");
        return host.createPackageContext(authority.substring(0, authority.length() - suffix.length()), 0);
    }
    static Context mediaContext(Context host, Uri media) throws PackageManager.NameNotFoundException {
        Context module = packageContext(host, media);
        // Media3's shader code belongs to SAO, while encoding executes under the
        // native app UID. Its Context must not resolve shader assets from Instagram
        // or try to use SAO's private storage under the wrong UID.
        return new ContextWrapper(host) {
            @Override public Resources getResources() { return module.getResources(); }
            @Override public AssetManager getAssets() { return module.getAssets(); }
            @Override public Context getApplicationContext() { return this; }
        };
    }
    private NativeModuleResources() { }
}
