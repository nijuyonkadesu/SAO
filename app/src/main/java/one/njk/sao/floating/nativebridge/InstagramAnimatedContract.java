package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.BooleanSupplier;

/** Inspected 451 local animated-attachment upload, separate from catalog-ID GIF sending. */
final class InstagramAnimatedContract {
    private final Field sessionField, mediaRestricted, mediaType, shareType, path, mime, width, height, directUpload, modernUpload, legacyUpload;
    private final Field pendingStore, pendingFactory, mutationFactory;
    private final Constructor<?> pendingConstructor, requestConstructor, commandConstructor;
    private final Method dimensions, aspect, usesModernUpload, metadata, pendingManager, register, startUpload;
    private final Method threadContext, threadSequence, mutationManager, dispatch;
    private final Object animatedType, directShareType;

    private InstagramAnimatedContract(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> session = loader.loadClass("com.instagram.common.session.UserSession");
        Class<?> key = loader.loadClass("com.instagram.model.direct.DirectThreadKey");
        Class<?> pending = loader.loadClass("X.03oB"), type = loader.loadClass("X.06uA");
        Class<?> share = loader.loadClass("com.instagram.pendingmedia.model.constants.ShareType");
        Class<?> descriptor = loader.loadClass("X.04Jj"), request = loader.loadClass("X.0Aqe"), thread = loader.loadClass("X.0PTm");
        Class<?> manager = loader.loadClass("com.instagram.pendingmedia.service.impl.PendingMediaManager");
        Class<?> store = loader.loadClass("X.07uj"), mutations = loader.loadClass("X.04vr"), command = loader.loadClass("X.0LE2");
        // The active, recipient-bound DM controller owns the session. GIF uploads do
        // not require the independent gallery/video callback contract to be present.
        sessionField = field(loader.loadClass("X.01SB"), "A0v", session, false);
        mediaRestricted = field(loader.loadClass("X.01ZC"), "A04", boolean.class, false);
        pendingConstructor = pending.getConstructor(String.class);
        mediaType = field(pending, "A10", type, false); shareType = field(pending, "A1r", share, false);
        path = field(pending, "A4k", String.class, false); mime = field(pending, "A4o", String.class, false);
        width = field(pending, "A0F", int.class, false); height = field(pending, "A0E", int.class, false);
        directUpload = field(pending, "A72", boolean.class, false);
        modernUpload = field(pending, "A6M", boolean.class, false); legacyUpload = field(pending, "A6I", boolean.class, false);
        animatedType = field(type, "A06", type, true).get(null);
        if (!(animatedType instanceof Enum<?> value) || !value.name().equals("ANIMATED_MEDIA"))
            throw new NoSuchFieldException("Animated media type changed");
        directShareType = field(share, "A0I", share, true).get(null);
        if (!(directShareType instanceof Enum<?> shareValue) || !shareValue.name().equals("DIRECT_SHARE"))
            throw new NoSuchFieldException("Direct share type changed");
        dimensions = method(pending, "A0Z", void.class, false, int.class, int.class);
        aspect = method(pending, "A0X", void.class, false, float.class);
        usesModernUpload = method(loader.loadClass("X.03rO"), "A02", boolean.class, true, session, pending);
        metadata = method(descriptor, "A00", descriptor, true, pending);
        pendingFactory = field(manager, "A0E", loader.loadClass("X.04mk"), true);
        pendingManager = method(pendingFactory.getType(), "A00", manager, false, Context.class, session);
        pendingStore = field(manager, "A0B", store, false);
        register = method(store, "A00", void.class, true, pending, store, boolean.class);
        startUpload = method(loader.loadClass("X.0POj"), "A00", void.class, true, session, key, pending, manager);
        threadContext = method(loader.loadClass("X.0POi"), "A00", thread, true, session, key);
        threadSequence = method(loader.loadClass("X.0POi"), "A01", Long.class, true, session, key);
        requestConstructor = request.getConstructor(thread, Boolean.class, String.class, String.class, String.class,
            boolean.class, boolean.class, boolean.class);
        commandConstructor = command.getConstructor(loader.loadClass("X.04Fd"),
            loader.loadClass("com.instagram.direct.model.genai.GenAIParamsMetadata"), request,
            loader.loadClass("X.0MM0"), loader.loadClass("X.0OxW"), descriptor, key, Boolean.class,
            Long.class, String.class, long.class, boolean.class, boolean.class);
        Class<?> base = loader.loadClass("X.0Ar3");
        if (!base.isAssignableFrom(command)) throw new NoSuchMethodException("Media command changed");
        mutationFactory = field(mutations, "A0T", loader.loadClass("X.04vs"), true);
        mutationManager = method(mutationFactory.getType(), "A01", mutations, false, session);
        dispatch = method(mutations, "A0F", boolean.class, false, base);
    }
    static InstagramAnimatedContract load(ClassLoader loader) {
        try { return new InstagramAnimatedContract(loader); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
    }
    Object session(Object controller) {
        try { return controller == null || !sessionField.getDeclaringClass().isInstance(controller) ? null : sessionField.get(controller); }
        catch (IllegalAccessException | RuntimeException ignored) { return null; }
    }
    boolean acceptsMedia(Object listener) {
        try { return mediaRestricted.getDeclaringClass().isInstance(listener) && !mediaRestricted.getBoolean(listener); }
        catch (IllegalAccessException | RuntimeException ignored) { return false; }
    }
    record LocalAnimation(Object pending, Object metadata) { }
    LocalAnimation localAnimation(InstagramGifFile gif) throws ReflectiveOperationException {
        Object pending = pendingConstructor.newInstance(java.util.UUID.randomUUID().toString());
        mediaType.set(pending, animatedType); shareType.set(pending, directShareType);
        path.set(pending, gif.file.getAbsolutePath()); mime.set(pending, "image/gif");
        width.setInt(pending, gif.width); height.setInt(pending, gif.height);
        dimensions.invoke(pending, gif.width, gif.height);
        aspect.invoke(pending, (float) gif.width / gif.height);
        directUpload.setBoolean(pending, true);
        return new LocalAnimation(pending, metadata.invoke(null, pending));
    }
    Object command(LocalAnimation animation, Object key, Object request, Long sequence) throws ReflectiveOperationException {
        // The ordinary media command waits for this local pending attachment's upload.
        // Never send a made-up GIPHY/Tenor ID through config_animated_media_message.
        return commandConstructor.newInstance(null, null, request, null, null, animation.metadata(), key,
            Boolean.FALSE, sequence, null, System.currentTimeMillis() * 1000, false, false);
    }
    Object request(Object thread) throws ReflectiveOperationException {
        return requestConstructor.newInstance(thread, null, null, null, null, false, false, false);
    }
    PreparedNativeContent prepare(Context context, Uri uri, Object session, Object key, BooleanSupplier valid) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Animation preparation must run on IO");
        InstagramGifFile gif = InstagramGifFile.prepare(context, uri);
        boolean prepared = false;
        try {
            LocalAnimation animation = localAnimation(gif);
            boolean modern = (Boolean) usesModernUpload.invoke(null, session, animation.pending());
            modernUpload.setBoolean(animation.pending(), modern); legacyUpload.setBoolean(animation.pending(), !modern);
            Object uploadManager = pendingManager.invoke(pendingFactory.get(null), context, session);
            Object queue = mutationManager.invoke(mutationFactory.get(null), session);
            Object command = command(animation, key, request(threadContext.invoke(null, session, key)),
                (Long) threadSequence.invoke(null, session, key));
            Object store = pendingStore.get(uploadManager);
            if (store == null || queue == null) throw new IllegalStateException("Animated upload unavailable");
            prepared = true;
            return new PreparedNativeContent() {
                private boolean attempted;
                @Override public boolean insert(android.widget.EditText view, android.view.inputmethod.InputConnection input) {
                    if (Looper.myLooper() != Looper.getMainLooper() || attempted) return false;
                    attempted = true;
                    if (!valid.getAsBoolean()) return false;
                    // First native side effect: retain the file even if its asynchronous
                    // upload/dispatch returns an unknown result. Never replay this request.
                    gif.submitted();
                    try {
                        register.invoke(null, animation.pending(), store, true);
                        startUpload.invoke(null, session, key, animation.pending(), uploadManager);
                        return Boolean.TRUE.equals(dispatch.invoke(queue, command));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return false; }
                }
                @Override public void close() { gif.close(); }
            };
        } finally { if (!prepared) gif.close(); }
    }
    private static Field field(Class<?> owner, String name, Class<?> type, boolean isStatic) throws ReflectiveOperationException {
        Field field = owner.getField(name);
        if (field.getType() != type || Modifier.isStatic(field.getModifiers()) != isStatic)
            throw new NoSuchFieldException("Animated attachment field changed");
        return field;
    }
    private static Method method(Class<?> owner, String name, Class<?> result, boolean isStatic, Class<?>... parameters) throws ReflectiveOperationException {
        // Match DEX class names directly; Java's hidden-API lint parser cannot model numeric names.
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getReturnType() != result ||
                Modifier.isStatic(method.getModifiers()) != isStatic || !java.util.Arrays.equals(method.getParameterTypes(), parameters)) continue;
            if (found != null) throw new NoSuchMethodException("Ambiguous animated attachment method");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("Animated attachment method changed");
        found.setAccessible(true); return found;
    }
}
