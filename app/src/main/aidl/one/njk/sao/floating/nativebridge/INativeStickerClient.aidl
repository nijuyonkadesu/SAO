package one.njk.sao.floating.nativebridge;
import android.os.Bundle;
import android.net.Uri;
import one.njk.sao.floating.nativebridge.INativeStateObserver;

interface INativeStickerClient {
    Bundle snapshot(boolean beginSelection);
    int insert(in Uri uri, String mimeType, String session, long requestId);
    oneway void setActive(boolean active, long generation);
    oneway void observe(INativeStateObserver observer);
}
