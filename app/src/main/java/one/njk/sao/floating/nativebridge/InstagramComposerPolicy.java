package one.njk.sao.floating.nativebridge;

import android.app.Activity;
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

/** Evidence from Instagram 451: keyboard images enter the DM handler; GIF is rejected. */
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
    private final Map<EditText, Binding> bindings = new WeakHashMap<>();

    InstagramComposerPolicy(ClassLoader loader) throws ClassNotFoundException {
        composerType = Class.forName("com.instagram.ui.widget.textview.ComposerAutoCompleteTextView", false, loader);
        keyType = Class.forName("com.instagram.model.direct.DirectThreadKey", false, loader);
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
    void configuredFromEditor(EditText view) {
        if (bindings.containsKey(view) || !isComposer(view)) return;
        // The host may configure its media listener before the module sees the setter.
        // Recover only the exact listener field identified by the named configuration API.
        for (java.lang.reflect.Method method : composerType.getMethods()) {
            if (!method.getName().equals("setInputContentInfoListener") || method.getParameterCount() != 2 ||
                method.getParameterTypes()[0] != String[].class) continue;
            Class<?> listenerType = method.getParameterTypes()[1];
            try {
                Object listener = null;
                for (Field field : composerType.getDeclaredFields()) {
                    if (field.getType() == listenerType && !Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true); listener = field.get(view); break;
                    }
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
}
