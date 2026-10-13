package one.njk.sao.floating.nativebridge;

import android.app.Activity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

/** Host-specific evidence for a media-capable conversation editor. Called only on Main. */
interface ComposerPolicy {
    boolean isComposer(EditText view);
    String identity(Activity activity, EditText view);
    String[] mimeTypes(EditorInfo info);
}
