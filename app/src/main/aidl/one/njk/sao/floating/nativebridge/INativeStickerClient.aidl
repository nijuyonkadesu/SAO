package one.njk.sao.floating.nativebridge;
import android.os.Bundle;
import android.net.Uri;

interface INativeStickerClient {
    Bundle snapshot(boolean beginSelection);
    boolean insert(in Uri uri, String mimeType, String session, long requestId);
    void setActive(boolean active, long generation);
}
