package one.njk.sao.floating.nativebridge;

import android.app.Activity;
import android.content.Context;
import android.content.ClipDescription;
import android.net.Uri;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

/** Editor capture/dispatch runs on Main; the captured preparation runs on a worker. */
interface ComposerPolicy {
    boolean isComposer(EditText view);
    String identity(Activity activity, EditText view);
    String[] mimeTypes(EditorInfo info);
    default String[] additionalMimeTypes(EditText view) { return new String[0]; }
    default String videoStatus() { return ""; }
    default NativeContentPreparation captureContent(Context context, EditText view, Uri uri, String mimeType) {
        return () -> prepareContent(context, uri, mimeType);
    }
    default PreparedNativeContent prepareContent(Context context, Uri uri, String mimeType) throws Exception {
        return (view, input) -> insertContent(view, input, uri, mimeType);
    }
    // A verified native receive-content view can remain usable after its IME closes.
    default String[] viewMimeTypes(EditText view) { return new String[0]; }
    default boolean insertContent(EditText view, InputConnection input, Uri uri, String mimeType) {
        if (input == null) return false;
        input.finishComposingText();
        return input.commitContent(new InputContentInfo(uri,
            new ClipDescription("SAO media", new String[]{mimeType}), null), 0, null);
    }
    // Draft-only editors may renew a selection after foreground typing. Never renew a
    // paused picker selection or a direct-send host whose conversation has changed.
    default boolean renewFocusedDraft() { return false; }
    default void reset() { }
}
