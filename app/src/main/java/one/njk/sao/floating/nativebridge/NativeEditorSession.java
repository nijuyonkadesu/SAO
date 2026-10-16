package one.njk.sao.floating.nativebridge;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
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
    private boolean receiveContent;
    private String identity;
    private String session;
    private String[] mimeTypes = new String[0];
    private String[] baseMimeTypes = new String[0];
    private long created;
    private long lastRequest;
    private String status = "waiting_editor";

    NativeEditorSession(ComposerPolicy policy, String modulePackage) {
        this(policy, modulePackage, SystemClock::elapsedRealtime);
    }
    NativeEditorSession(ComposerPolicy policy, String modulePackage, LongSupplier clock) {
        this.policy = policy; this.authority = modulePackage + ".fileprovider"; this.clock = clock;
    }
    boolean owns(EditText view) { return editor.get() == view; }
    void clear() {
        input = null; receiveContent = false; session = null; identity = null; mimeTypes = new String[0]; baseMimeTypes = new String[0];
        editor.clear(); activity.clear(); status = "waiting_editor";
    }
    void captureView(Activity current, EditText view) {
        String[] types = policy.viewMimeTypes(view);
        if (types.length == 0) return;
        capture(current, view, null, types, true);
    }
    void capture(Activity current, EditText view, InputConnection connection, EditorInfo info) {
        String[] types = policy.viewMimeTypes(view);
        boolean receiver = types.length > 0;
        capture(current, view, connection, receiver ? types : policy.mimeTypes(info), receiver);
    }
    private void capture(Activity current, EditText view, InputConnection connection, String[] types, boolean receiver) {
        String[] base = types;
        types = combinedTypes(types, policy.additionalMimeTypes(view));
        String nextIdentity = policy.isComposer(view) ? policy.identity(current, view) : null;
        if ((!receiver && connection == null) || !policy.isComposer(view) || types.length == 0) { clear(); status = "unsupported_mime"; return; }
        // Retain the naturally created wrapper while the host finishes loading its metadata.
        // No lease exists until that metadata is verified. A changed established identity
        // still clears the wrapper and cannot reuse an old conversation selection.
        if (activity.get() != current || editor.get() != view || nextIdentity == null || !nextIdentity.equals(identity) || !Arrays.equals(types, mimeTypes) || receiveContent != receiver) session = null;
        activity = new WeakReference<>(current); editor = new WeakReference<>(view);
        input = connection; receiveContent = receiver; identity = nextIdentity; baseMimeTypes = base.clone(); mimeTypes = types.clone();
    }
    Bundle snapshot(Activity current, boolean resumed) {
        Bundle state = new Bundle();
        EditText view = editor.get();
        if (input == null && !receiveContent) { state.putString("status", status); return state; }
        if (!sameEditor(current, view)) { clear(); state.putString("status", status); return state; }
        String[] supported = combinedTypes(baseMimeTypes, policy.additionalMimeTypes(view));
        if (!Arrays.equals(supported, mimeTypes)) { mimeTypes = supported; session = null; }
        String actual = policy.identity(current, view);
        boolean ready = resumed && (view.hasFocus() || receiveContent) && current.hasWindowFocus();
        if (identity == null) {
            if (actual == null) { status = "pending_metadata"; state.putString("status", status); return state; }
            identity = actual;
        } else if (actual == null || !actual.equals(identity)) {
            if (actual != null && ready && policy.renewFocusedDraft()) {
                identity = actual; session = null; // Fresh foreground selection; the old token is revoked.
            } else {
                clear(); status = "editor_changed"; state.putString("status", status); return state;
            }
        }
        status = ready ? "ready" : "unfocused";
        state.putString("status", status);
        if (session != null && clock.getAsLong() - created > MAX_AGE_MS) session = null;
        if (ready && session == null) { session = UUID.randomUUID().toString(); created = clock.getAsLong(); }
        if (session != null) {
            state.putString("session", session); state.putLong("created", created);
            state.putBoolean("resumed", ready); state.putStringArray("mime_types", mimeTypes.clone());
        }
        return state;
    }
    private boolean sameEditor(Activity current, EditText view) {
        return current != null && activity.get() == current && view != null && view.isAttachedToWindow() && view.isShown() && (input != null || receiveContent) && view.isEnabled() &&
            view.getRootView() == current.getWindow().getDecorView().getRootView() && policy.isComposer(view) && (!receiveContent || Arrays.equals(baseMimeTypes, policy.viewMimeTypes(view)));
    }
    private boolean validEditor(Activity current, EditText view) {
        if (!sameEditor(current, view) || identity == null) return false;
        String actual = policy.identity(current, view);
        return actual != null && actual.equals(identity);
    }
    boolean insert(Activity current, boolean resumed, Uri uri, String mimeType, String expected, long requestId) {
        return insert(current, resumed, uri, mimeType, expected, requestId,
            (view, connection) -> policy.insertContent(view, connection, uri, mimeType));
    }
    boolean accepts(Activity current, boolean resumed, Uri uri, String mimeType, String expected, long requestId) {
        if (uri == null || !"content".equals(uri.getScheme()) || !authority.equals(uri.getAuthority()) ||
            !Arrays.asList(mimeTypes).contains(mimeType)) return false;
        EditText view = editor.get();
        if (!validEditor(current, view)) { clear(); return false; }
        if (!resumed || !current.hasWindowFocus() || (!view.hasFocus() && !receiveContent) || expected == null || !expected.equals(session) ||
            requestId <= lastRequest || clock.getAsLong() - created > MAX_AGE_MS) return false;
        return Arrays.asList(combinedTypes(baseMimeTypes, policy.additionalMimeTypes(view))).contains(mimeType);
    }
    boolean insert(Activity current, boolean resumed, Uri uri, String mimeType, String expected, long requestId, PreparedNativeContent content) {
        if (!accepts(current, resumed, uri, mimeType, expected, requestId)) return false;
        lastRequest = requestId;
        session = null; // Consume before dispatch; a false/unknown host result must never be replayed.
        // SAO grants URI access explicitly. The policy chooses the host's existing IME
        // wrapper or its verified native receive-content view; there is no fabricated token.
        return content.insert(editor.get(), input);
    }
    private static String[] combinedTypes(String[] base, String[] extra) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>(Arrays.asList(base));
        result.addAll(Arrays.asList(extra));
        return result.toArray(new String[0]);
    }
}
