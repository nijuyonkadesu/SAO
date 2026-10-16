package one.njk.sao.floating.nativebridge;

import android.view.inputmethod.InputConnection;
import android.widget.EditText;

/** Prepared on the Binder worker, dispatched once on Main after revalidating the editor. */
interface PreparedNativeContent extends AutoCloseable {
    boolean insert(EditText view, InputConnection input);
    @Override default void close() { }
}
