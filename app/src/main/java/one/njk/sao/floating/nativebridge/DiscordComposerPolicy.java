package one.njk.sao.floating.nativebridge;

import android.app.Activity;
import android.content.ClipDescription;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;

/** Native receive-content insertion stages images/GIFs in Discord's focused draft; never Send. */
final class DiscordComposerPolicy implements ComposerPolicy {
    private final Class<?> editorType;
    private final Class<?> rootType;
    private final Field editorField;
    private final Field revisionField;
    private final Method listenerGetter;

    DiscordComposerPolicy(ClassLoader loader) throws ReflectiveOperationException {
        editorType = Class.forName("com.discord.chat.input.views.DCDChatInput", false, loader);
        rootType = Class.forName("com.discord.chat.input.views.ChatInputRootView", false, loader);
        editorField = rootType.getDeclaredField("editText"); editorField.setAccessible(true);
        revisionField = rootType.getDeclaredField("lastEditId"); revisionField.setAccessible(true);
        listenerGetter = rootType.getMethod("getListener");
    }
    private Object owner(EditText view) {
        if (!editorType.isInstance(view)) return null;
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
            if (rootType.isInstance(parent)) return parent;
        return null;
    }
    @Override public boolean isComposer(EditText view) {
        Object owner = owner(view);
        try { return owner != null && editorField.get(owner) == view && listenerGetter.invoke(owner) != null; }
        catch (ReflectiveOperationException | RuntimeException error) { return false; }
    }
    @Override public String identity(Activity current, EditText view) {
        Object root = owner(view); if (root == null || !isComposer(view)) return null;
        try {
            Object revision = revisionField.get(root); Object listener = listenerGetter.invoke(root);
            if (!(revision instanceof String) || ((String) revision).isEmpty()) return null;
            // Only the opaque draft revision and object ownership are inspected. No message text,
            // recipient lists or guessed channel IDs. Native draft replacement revokes the lease.
            String token = System.identityHashCode(root) + ":" + System.identityHashCode(listener) + ":" + revision;
            return Arrays.toString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { return null; }
    }
    @Override public boolean renewFocusedDraft() { return true; }
    @Override public String[] mimeTypes(EditorInfo info) {
        ArrayList<String> result = new ArrayList<>();
        if (info.contentMimeTypes != null) for (String mime : new String[]{"image/jpeg", "image/png", "image/gif"})
            for (String declared : info.contentMimeTypes)
                if (ClipDescription.compareMimeTypes(mime, declared)) { result.add(mime); break; }
        return result.toArray(new String[0]);
    }
}
