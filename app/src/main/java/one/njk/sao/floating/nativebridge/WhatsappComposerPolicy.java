package one.njk.sao.floating.nativebridge;

import android.app.Activity;
import android.content.ClipDescription;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WhatsappComposerPolicy implements ComposerPolicy {
    static final String STICKER_MIME = "image/webp.wasticker";
    private final Map<Class<?>, List<Field>> conversationFields = new HashMap<>();

    @Override public boolean isComposer(EditText view) {
        try { return "entry".equals(view.getResources().getResourceEntryName(view.getId())); }
        catch (android.content.res.Resources.NotFoundException error) { return false; }
    }
    @Override public String[] mimeTypes(EditorInfo info) {
        List<String> accepted = new ArrayList<>();
        if (info.contentMimeTypes != null) {
            // Only the exact WhatsApp declaration proves native sticker capability.
            for (String declared : info.contentMimeTypes) if (STICKER_MIME.equals(declared)) { accepted.add(STICKER_MIME); break; }
            for (String mime : new String[]{"image/jpeg", "image/png", "image/gif"})
                for (String declared : info.contentMimeTypes)
                    if (ClipDescription.compareMimeTypes(mime, declared)) { accepted.add(mime); break; }
        }
        return accepted.toArray(new String[0]);
    }
    @Override public String identity(Activity current, EditText view) {
        try {
            List<Field> fields = conversationFields.get(view.getClass());
            if (fields == null) {
                fields = new ArrayList<>();
                for (Class<?> type = view.getClass(); type != null && !type.getName().startsWith("android."); type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (field.getType() == Bundle.class && !Modifier.isStatic(field.getModifiers())) {
                            field.setAccessible(true); fields.add(field);
                        }
                    }
                }
                conversationFields.put(view.getClass(), fields);
            }
            String composerJid = null;
            for (Field field : fields) {
                Bundle metadata = (Bundle) field.get(view);
                String candidate = metadata == null ? null : metadata.getString("ARG_JID");
                if (candidate == null || candidate.isEmpty()) continue;
                if (composerJid != null && !composerJid.equals(candidate)) return null;
                composerJid = candidate;
            }
            if (composerJid == null) return null;
            // The current composer's metadata identifies its chat. Activity launch extras
            // may be absent or stale when navigating conversations within the same activity.
            byte[] bytes = composerJid.getBytes(StandardCharsets.UTF_8);
            return Arrays.toString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) { return null; }
    }
}
