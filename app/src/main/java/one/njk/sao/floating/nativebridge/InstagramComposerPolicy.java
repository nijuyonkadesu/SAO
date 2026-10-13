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
    private record Binding(WeakReference<Object> listener, Field listenerField, InstagramThreadBinding thread) {}
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
            if (thread != null) bindings.put(view, new Binding(new WeakReference<>(listener), listenerField, thread));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { /* Unsupported shapes stay disabled. */ }
    }
    @Override public String identity(Activity current, EditText view) {
        Binding binding = bindings.get(view);
        if (binding == null || !isComposer(view)) return null;
        try {
            Object listener = binding.listener().get();
            if (listener == null || binding.listenerField().get(view) != listener) return null;
            return binding.thread().identity(view);
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
