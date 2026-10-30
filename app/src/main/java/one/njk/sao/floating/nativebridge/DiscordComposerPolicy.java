package one.njk.sao.floating.nativebridge;

import android.app.Activity;
import android.content.ClipDescription;
import android.content.ClipData;
import android.net.Uri;
import android.os.Build;
import android.view.ContentInfo;
import android.view.inputmethod.InputConnection;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;

/** Native content insertion stages attachments in the leased Discord draft; never Send. */
final class DiscordComposerPolicy implements ComposerPolicy {
    private final Class<?> editorType;
    private final Class<?> rootType;
    private final Field editorField;
    private final Field revisionField;
    private final Method listenerGetter;
    private volatile DiscordAttachmentContract attachments;

    DiscordComposerPolicy(ClassLoader loader) throws ReflectiveOperationException {
        this(loader, Runnable::run);
    }
    DiscordComposerPolicy(ClassLoader loader, java.util.concurrent.Executor io) throws ReflectiveOperationException {
        editorType = Class.forName("com.discord.chat.input.views.DCDChatInput", false, loader);
        rootType = Class.forName("com.discord.chat.input.views.ChatInputRootView", false, loader);
        editorField = rootType.getDeclaredField("editText"); editorField.setAccessible(true);
        revisionField = rootType.getDeclaredField("lastEditId"); revisionField.setAccessible(true);
        listenerGetter = rootType.getMethod("getListener");
        io.execute(() -> attachments = DiscordAttachmentContract.load(loader));
    }
    EditText configuredEditor(Object root) {
        try { return rootType.isInstance(root) ? (EditText) editorField.get(root) : null; }
        catch (IllegalAccessException | ClassCastException error) { return null; }
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
    @Override public String[] additionalMimeTypes(EditText view) {
        return attachments != null && isComposer(view) && attachments.supports(view.getContext()) ?
            new String[]{"video/mp4", "video/webm"} : new String[0];
    }
    @Override public PreparedNativeContent prepareContent(android.content.Context context, Uri uri, String mime) throws Exception {
        if (!NativeVideoMetadata.supports(mime)) return ComposerPolicy.super.prepareContent(context, uri, mime);
        DiscordAttachmentContract contract = attachments;
        if (contract == null) return null;
        NativeVideoMetadata metadata = NativeVideoMetadata.read(context, uri, mime);
        return (view, input) -> {
            Object root = owner(view);
            if (!(root instanceof android.view.View) || !isComposer(view) || !contract.supports(view.getContext())) return false;
            if (!view.hasFocus() && !view.requestFocus()) return false;
            return contract.insert((android.view.View) root, uri, mime, metadata);
        };
    }
    @Override public String[] mimeTypes(EditorInfo info) { return supportedTypes(info.contentMimeTypes); }
    @Override public String[] viewMimeTypes(EditText view) {
        return Build.VERSION.SDK_INT >= 31 && isComposer(view) ? supportedTypes(view.getReceiveContentMimeTypes()) : new String[0];
    }
    @Override public boolean insertContent(EditText view, InputConnection input, Uri uri, String mimeType) {
        if (Build.VERSION.SDK_INT < 31 || viewMimeTypes(view).length == 0)
            return ComposerPolicy.super.insertContent(view, input, uri, mimeType);
        if (!Arrays.asList(viewMimeTypes(view)).contains(mimeType) || !isComposer(view)) return false;
        // Restore only the leased native editor on explicit Paste. No IME is constructed,
        // no keyboard is forcibly opened, and Discord's own receive-content callback stages
        // the attachment. Focusing first preserves native focus/image event ordering for JS.
        if (!view.hasFocus() && !view.requestFocus()) return false;
        ClipData clip = new ClipData(new ClipDescription("SAO media", new String[]{mimeType}), new ClipData.Item(uri));
        return view.performReceiveContent(new ContentInfo.Builder(clip, ContentInfo.SOURCE_APP).build()) == null;
    }
    private static String[] supportedTypes(String[] declaredTypes) {
        ArrayList<String> result = new ArrayList<>();
        if (declaredTypes != null) for (String mime : new String[]{"image/jpeg", "image/png", "image/gif"})
            for (String declared : declaredTypes)
                if (ClipDescription.compareMimeTypes(mime, declared)) { result.add(mime); break; }
        return result.toArray(new String[0]);
    }
}
