package one.njk.sao.floating.nativebridge;
import android.os.Bundle;

/** Updates are subscribed only while the picker or an explicit insertion needs them. */
oneway interface INativeStateObserver {
    void changed(in Bundle state);
}
