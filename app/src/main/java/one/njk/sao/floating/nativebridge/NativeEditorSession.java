package one.njk.sao.floating.nativebridge;

import android.app.Activity;
import android.content.ClipDescription;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.widget.EditText;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.LongSupplier;

/** A short-lived, one-use editor lease. All editor operations run on the host Main thread. */
final class NativeEditorSession {
    static final long MAX_AGE_MS = 120_000;
    private final ComposerPolicy policy;
    private final String authority;
    private final LongSupplier clock;
    private WeakReference<Activity> activity = new WeakReference<>(null);
    private WeakReference<EditText> editor = new WeakReference<>(null);
    private InputConnection input;
    private String identity;
    private String session;
    private String[] mimeTypes = new String[0];
    private long created;
    private long lastRequest;

    NativeEditorSession(ComposerPolicy policy, String modulePackage) {
        this(policy, modulePackage, SystemClock::elapsedRealtime);
    }
    NativeEditorSession(ComposerPolicy policy, String modulePackage, LongSupplier clock) {
        this.policy = policy; this.authority = modulePackage + ".fileprovider"; this.clock = clock;
    }
    boolean owns(EditText view) { return editor.get() == view; }
    void clear() {
        input = null; session = null; identity = null; mimeTypes = new String[0];
        editor.clear(); activity.clear();
    }
    void capture(Activity current, EditText view, InputConnection connection, EditorInfo info) {
        String nextIdentity = policy.isComposer(view) ? policy.identity(current, view) : null;
        String[] types = policy.mimeTypes(info);
        if (connection == null || nextIdentity == null || types.length == 0) { clear(); return; }
        if (activity.get() != current || editor.get() != view || !nextIdentity.equals(identity) || !Arrays.equals(types, mimeTypes)) session = null;
        activity = new WeakReference<>(current); editor = new WeakReference<>(view);
        input = connection; identity = nextIdentity; mimeTypes = types.clone();
    }
    Bundle snapshot(Activity current, boolean resumed) {
        Bundle state = new Bundle();
        EditText view = editor.get();
        if (!validEditor(current, view)) { clear(); return state; }
        boolean ready = resumed && view.hasFocus() && current.hasWindowFocus();
        if (session != null && clock.getAsLong() - created > MAX_AGE_MS) session = null;
        if (ready && session == null) { session = UUID.randomUUID().toString(); created = clock.getAsLong(); }
        if (session != null) {
            state.putString("session", session); state.putLong("created", created);
            state.putBoolean("resumed", ready); state.putStringArray("mime_types", mimeTypes.clone());
        }
        return state;
    }
    private boolean validEditor(Activity current, EditText view) {
        if (current == null || activity.get() != current || view == null || !view.isAttachedToWindow() || input == null ||
            view.getRootView() != current.getWindow().getDecorView().getRootView() || !policy.isComposer(view)) return false;
        String actual = policy.identity(current, view);
        return actual != null && actual.equals(identity);
    }
    boolean insert(Activity current, boolean resumed, Uri uri, String mimeType, String expected, long requestId) {
        if (uri == null || !"content".equals(uri.getScheme()) || !authority.equals(uri.getAuthority()) ||
            !Arrays.asList(mimeTypes).contains(mimeType)) return false;
        EditText view = editor.get();
        if (!validEditor(current, view)) { clear(); return false; }
        if (!resumed || !current.hasWindowFocus() || !view.hasFocus() || expected == null || !expected.equals(session) ||
            requestId <= lastRequest || clock.getAsLong() - created > MAX_AGE_MS) return false;
        lastRequest = requestId;
        session = null; // Consume before dispatch; a false/unknown host result must never be replayed.
        input.finishComposingText();
        // SAO grants URI access explicitly. There is no fabricated IME permission token.
        return input.commitContent(new InputContentInfo(uri,
            new ClipDescription("SAO media", new String[]{mimeType}), null), 0, null);
    }
}
