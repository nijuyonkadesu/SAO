package one.njk.sao.floating.nativebridge;

import android.app.Activity;
import android.content.Context;
import android.content.ClipDescription;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Inspected Instagram 435/451: keyboard images enter the DM handler; GIF is rejected. */
final class InstagramComposerPolicy implements ComposerPolicy {
    private static final class Binding {
        final WeakReference<Object> listener; final Field listenerField;
        InstagramThreadBinding thread; int attempts; long nextAttempt;
        Binding(Object listener, Field field, InstagramThreadBinding thread) {
            this.listener = new WeakReference<>(listener); this.listenerField = field;
            this.thread = thread; this.attempts = 1; this.nextAttempt = android.os.SystemClock.elapsedRealtime() + 250;
        }
    }
    private final Class<?> composerType;
    private final Class<?> keyType;
    private volatile InstagramGalleryContract gallery;
    private volatile InstagramAnimatedContract animated;
    private volatile boolean galleryLoaded;
    private final Map<EditText, Binding> bindings = new WeakHashMap<>();

    InstagramComposerPolicy(ClassLoader loader) throws ClassNotFoundException {
        this(loader, Runnable::run);
    }
    InstagramComposerPolicy(ClassLoader loader, java.util.concurrent.Executor io) throws ClassNotFoundException {
        composerType = Class.forName("com.instagram.ui.widget.textview.ComposerAutoCompleteTextView", false, loader);
        keyType = Class.forName("com.instagram.model.direct.DirectThreadKey", false, loader);
        io.execute(() -> {
            gallery = InstagramGalleryContract.load(loader);
            animated = InstagramAnimatedContract.load(loader);
            galleryLoaded = true;
        });
    }
    @Override public boolean isComposer(EditText view) {
        if (!composerType.isInstance(view)) return false;
        try { return "row_thread_composer_edittext".equals(view.getResources().getResourceEntryName(view.getId())); }
        catch (android.content.res.Resources.NotFoundException error) { return false; }
    }
    void configured(EditText view, Object listener, Class<?> listenerType) {
        bindings.remove(view);
        if (listener == null || !isComposer(view)) return;
        try {
            Field listenerField = null;
            for (Class<?> type = view.getClass(); type != null && type.getName().startsWith("com.instagram."); type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType() != listenerType || Modifier.isStatic(field.getModifiers())) continue;
                    if (listenerField != null) return;
                    field.setAccessible(true); listenerField = field;
                }
            }
            if (listenerField == null || listenerField.get(view) != listener) return;
            InstagramThreadBinding thread = InstagramThreadBinding.discover(listener, view, keyType);
            bindings.put(view, new Binding(listener, listenerField, thread));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { /* Unsupported shapes stay disabled. */ }
    }
    @Override public void reset() { bindings.clear(); }
    void configuredFromEditor(EditText view) {
        configuredFromEditor(view, false);
    }
    void configuredFromEditor(EditText view, boolean explicitSelection) {
        if (!isComposer(view)) return;
        Binding existing = bindings.get(view);
        if (existing != null) try {
            Object listener = existing.listener.get();
            if (listener != null && existing.listenerField.get(view) == listener) {
                if (explicitSelection && (existing.thread == null || existing.thread.controller(view) == null)) {
                    // The listener can precede the controller's editor assignment. The
                    // bounded background attempts must not disable this composer forever.
                    // One explicit opening permits one new bounded discovery; no idle loop.
                    existing.thread = InstagramThreadBinding.discover(listener, view, keyType);
                    existing.attempts = 1; existing.nextAttempt = android.os.SystemClock.elapsedRealtime() + 250;
                }
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        bindings.remove(view);
        // The host may configure its media listener before the module sees the setter.
        // Recover only the exact listener field identified by the named configuration API.
        for (java.lang.reflect.Method method : composerType.getMethods()) {
            if (!method.getName().equals("setInputContentInfoListener") || method.getParameterCount() != 2 ||
                method.getParameterTypes()[0] != String[].class) continue;
            Class<?> listenerType = method.getParameterTypes()[1];
            try {
                Object listener = null;
                for (Class<?> type = view.getClass(); type != null && type.getName().startsWith("com.instagram."); type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (field.getType() == listenerType && !Modifier.isStatic(field.getModifiers())) {
                            field.setAccessible(true); listener = field.get(view); break;
                        }
                    }
                    if (listener != null) break;
                }
                configured(view, listener, listenerType);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
            return;
        }
    }
    @Override public String identity(Activity current, EditText view) {
        Binding binding = bindings.get(view);
        if (binding == null || !isComposer(view)) return null;
        try {
            Object listener = binding.listener.get();
            if (listener == null || binding.listenerField.get(view) != listener) return null;
            if (binding.thread == null && binding.attempts < 3 && android.os.SystemClock.elapsedRealtime() >= binding.nextAttempt) {
                binding.attempts++; binding.nextAttempt = android.os.SystemClock.elapsedRealtime() + 1000;
                binding.thread = InstagramThreadBinding.discover(listener, view, keyType);
            }
            return binding.thread == null ? null : binding.thread.identity(view);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
    }
    @Override public String[] mimeTypes(EditorInfo info) {
        List<String> result = new ArrayList<>();
        if (info.contentMimeTypes != null) {
            for (String mime : new String[]{"image/jpeg", "image/png"})
                for (String declared : info.contentMimeTypes)
                    if (ClipDescription.compareMimeTypes(mime, declared)) { result.add(mime); break; }
        }
        // Explicitly exclude GIF/video, even if a future/broad editor declaration includes them.
        return result.toArray(new String[0]);
    }
    private Object videoSender(EditText view) {
        if (gallery == null) return null;
        Binding binding = bindings.get(view);
        return binding == null || binding.thread == null || !gallery.acceptsMedia(binding.listener.get()) ? null : gallery.sender(binding.thread.controller(view));
    }
    @Override public String[] additionalMimeTypes(EditText view) {
        Object sender = videoSender(view);
        if (sender == null) return new String[0];
        return animated != null && animated.session(sender) != null
            ? new String[]{"video/mp4", "video/webm", "image/gif"} : new String[]{"video/mp4", "video/webm"};
    }
    @Override public String[] viewMimeTypes(EditText view) {
        // Native media delivery belongs to the verified DM controller, independently
        // of keyboard focus. Still photos keep the naturally captured IME connection.
        return isComposer(view) && identity(null, view) != null ? additionalMimeTypes(view) : new String[0];
    }
    @Override public String videoStatus() {
        if (!galleryLoaded) return "gallery_loading";
        if (gallery == null) return "gallery_unavailable";
        EditText editor = null; Binding selected = null;
        for (Map.Entry<EditText, Binding> entry : bindings.entrySet()) {
            EditText view = entry.getKey();
            if (view == null || !view.isAttachedToWindow() || !view.isShown() || !isComposer(view)) continue;
            if (editor != null) return "ambiguous_composer";
            editor = view; selected = entry.getValue();
        }
        if (selected == null) return "waiting_editor";
        try {
            Object listener = selected.listener.get();
            if (listener == null || selected.listenerField.get(editor) != listener) return "waiting_editor";
            if (selected.thread == null || selected.thread.controller(editor) == null) return "thread_unresolved";
            if (selected.thread.identity(editor) == null) return "pending_metadata";
            if (!gallery.acceptsMedia(listener)) return "media_restricted";
            return gallery.sender(selected.thread.controller(editor)) == null ? "sender_missing" : "ready";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return "thread_unresolved"; }
    }
    @Override public NativeContentPreparation captureContent(Context context, EditText view, android.net.Uri uri, String mimeType) {
        if (!"image/gif".equals(mimeType)) return ComposerPolicy.super.captureContent(context, view, uri, mimeType);
        InstagramAnimatedContract contract = animated;
        Binding binding = bindings.get(view);
        Object sender = videoSender(view);
        String originalIdentity = identity(null, view);
        if (contract == null || binding == null || binding.thread == null || sender == null || originalIdentity == null) return null;
        try {
            Object key = binding.thread.copyKey(view);
            Object session = contract.session(sender);
            if (key == null || session == null) return null;
            WeakReference<EditText> original = new WeakReference<>(view);
            return () -> contract.prepare(context, uri, session, key, () -> {
                EditText current = original.get();
                return current != null && bindings.get(current) == binding &&
                    originalIdentity.equals(identity(null, current)) && videoSender(current) == sender && contract.session(sender) == session;
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
    }
    @Override public PreparedNativeContent prepareContent(Context context, android.net.Uri uri, String mimeType) throws Exception {
        // A GIF must enter the recipient-bound animated upload adapter, never the bitmap IME path.
        if ("image/gif".equals(mimeType)) return null;
        if (!NativeVideoMetadata.supports(mimeType)) return ComposerPolicy.super.prepareContent(context, uri, mimeType);
        if (gallery == null) return null;
        InstagramVideoFile video = InstagramVideoFile.prepare(context, uri, mimeType);
        return new PreparedNativeContent() {
            @Override public boolean insert(EditText view, android.view.inputmethod.InputConnection input) {
                Object sender = videoSender(view);
                if (sender == null) return false;
                Binding originalBinding = bindings.get(view);
                String originalIdentity = identity(null, view);
                WeakReference<EditText> originalEditor = new WeakReference<>(view);
                if (originalBinding == null || originalIdentity == null) return false;
                try {
                    // Once dispatch begins, a false/unknown result is never retried or cleaned
                    // out from under an upload which the host may already have scheduled.
                    video.submitted();
                    Object selected = gallery.selectedVideo(video);
                    gallery.send(sender, selected, () -> {
                        EditText current = originalEditor.get();
                        return current != null && current.isAttachedToWindow() && current.isShown() &&
                            bindings.get(current) == originalBinding && originalIdentity.equals(identity(null, current)) && videoSender(current) == sender;
                    });
                    return true;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return false; }
            }
            @Override public void close() { video.close(); }
        };
    }
}
