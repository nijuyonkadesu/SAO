package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.BooleanSupplier;

/** Inspected local animated-attachment uploads, separate from catalog-ID GIF sending. */
final class InstagramAnimatedContract {
    private final Field sessionField, mediaRestricted, mediaType, shareType, path, width, height, aspectField, directUpload, modernUpload, legacyUpload;
    private final Field pendingStore, pendingFactory, mutationFactory;
    private final Constructor<?> pendingConstructor, requestConstructor, commandConstructor;
    private final Method dimensions, aspect, usesModernUpload, metadata, pendingManager, register, startUpload;
    private final Method threadContext, threadSequence, mutationManager, dispatch;
    private final Object animatedType, directShareType;

    private record Host(String owner, String session, String listener) { }
    private record Pending(String type, String mediaEnum, String mediaField, String shareField,
        String path, String dimensions, String aspect, String direct, String modern, String legacy) { }
    private record Upload(String policy, String factory, String store, String start) { }
    private record Command(String metadata, String request, String context, String contextFactory,
        String type, String content, String extra, String options, String queue, String queueFactory,
        String base, String dispatch) { }
    private record Shape(Host host, Pending pending, Upload upload, Command command) { }
    // Select by complete field/method signatures, never the app's reported version.
    // 447 was inspected from the installed device APK; 451 from its original APK.
    private static final Shape[] SHAPES = {
        new Shape(new Host("X.0q6", "A0t", "X.15C"),
            new Pending("X.1jR", "X.6A8", "A12", "A1s", "A4n", "A0U", null, "A74", "A6P", "A6L"),
            new Upload("X.5K2", "X.4gw", "X.0TW", "X.Nkb"),
            new Command("X.2lR", "X.7Uq", "X.Jg6", "X.Iy0", "X.AOq", "X.0tT", "X.IyK", "X.OyD",
                "X.4sa", "X.4sd", "X.A1x", "A0E")),
        new Shape(new Host("X.01SB", "A0v", "X.01ZC"),
            new Pending("X.03oB", "X.06uA", "A10", "A1r", "A4k", "A0Z", "A0X", "A72", "A6M", "A6I"),
            new Upload("X.03rO", "X.04mk", "X.07uj", "X.0POj"),
            new Command("X.04Jj", "X.0Aqe", "X.0PTm", "X.0POi", "X.0LE2", "X.04Fd", "X.0MM0", "X.0OxW",
                "X.04vr", "X.04vs", "X.0Ar3", "A0F"))
    };

    private InstagramAnimatedContract(ClassLoader loader, Shape shape) throws ReflectiveOperationException {
        Class<?> session = loader.loadClass("com.instagram.common.session.UserSession");
        Class<?> key = loader.loadClass("com.instagram.model.direct.DirectThreadKey");
        Pending symbols = shape.pending();
        Command send = shape.command();
        Class<?> pending = loader.loadClass(symbols.type()), type = loader.loadClass(symbols.mediaEnum());
        Class<?> share = loader.loadClass("com.instagram.pendingmedia.model.constants.ShareType");
        Class<?> descriptor = loader.loadClass(send.metadata()), request = loader.loadClass(send.request()), thread = loader.loadClass(send.context());
        Class<?> manager = loader.loadClass("com.instagram.pendingmedia.service.impl.PendingMediaManager");
        Class<?> store = loader.loadClass(shape.upload().store()), mutations = loader.loadClass(send.queue()), command = loader.loadClass(send.type());
        // The active, recipient-bound DM controller owns the session. GIF uploads do
        // not require the independent gallery/video callback contract to be present.
        sessionField = field(loader.loadClass(shape.host().owner()), shape.host().session(), session, false);
        mediaRestricted = field(loader.loadClass(shape.host().listener()), "A04", boolean.class, false);
        pendingConstructor = pending.getConstructor(String.class);
        mediaType = field(pending, symbols.mediaField(), type, false); shareType = field(pending, symbols.shareField(), share, false);
        path = field(pending, symbols.path(), String.class, false);
        width = field(pending, "A0F", int.class, false); height = field(pending, "A0E", int.class, false);
        directUpload = field(pending, symbols.direct(), boolean.class, false);
        modernUpload = field(pending, symbols.modern(), boolean.class, false); legacyUpload = field(pending, symbols.legacy(), boolean.class, false);
        animatedType = field(type, "A06", type, true).get(null);
        if (!(animatedType instanceof Enum<?> value) || !value.name().equals("ANIMATED_MEDIA"))
            throw new NoSuchFieldException("Animated media type changed");
        directShareType = field(share, "A0I", share, true).get(null);
        if (!(directShareType instanceof Enum<?> shareValue) || !shareValue.name().equals("DIRECT_SHARE"))
            throw new NoSuchFieldException("Direct share type changed");
        dimensions = method(pending, symbols.dimensions(), void.class, false, int.class, int.class);
        aspectField = field(pending, "A02", float.class, false);
        aspect = symbols.aspect() == null ? null : method(pending, symbols.aspect(), void.class, false, float.class);
        usesModernUpload = method(loader.loadClass(shape.upload().policy()), "A02", boolean.class, true, session, pending);
        metadata = method(descriptor, "A00", descriptor, true, pending);
        pendingFactory = field(manager, "A0E", loader.loadClass(shape.upload().factory()), true);
        pendingManager = method(pendingFactory.getType(), "A00", manager, false, Context.class, session);
        pendingStore = field(manager, "A0B", store, false);
        register = method(store, "A00", void.class, true, pending, store, boolean.class);
        startUpload = method(loader.loadClass(shape.upload().start()), "A00", void.class, true, session, key, pending, manager);
        threadContext = method(loader.loadClass(send.contextFactory()), "A00", thread, true, session, key);
        threadSequence = method(loader.loadClass(send.contextFactory()), "A01", Long.class, true, session, key);
        requestConstructor = request.getConstructor(thread, Boolean.class, String.class, String.class, String.class,
            boolean.class, boolean.class, boolean.class);
        commandConstructor = command.getConstructor(loader.loadClass(send.content()),
            loader.loadClass("com.instagram.direct.model.genai.GenAIParamsMetadata"), request,
            loader.loadClass(send.extra()), loader.loadClass(send.options()), descriptor, key, Boolean.class,
            Long.class, String.class, long.class, boolean.class, boolean.class);
        Class<?> base = loader.loadClass(send.base());
        if (!base.isAssignableFrom(command)) throw new NoSuchMethodException("Media command changed");
        mutationFactory = field(mutations, "A0T", loader.loadClass(send.queueFactory()), true);
        mutationManager = method(mutationFactory.getType(), "A01", mutations, false, session);
        dispatch = method(mutations, send.dispatch(), boolean.class, false, base);
    }
    static InstagramAnimatedContract load(ClassLoader loader) {
        InstagramAnimatedContract found = null;
        for (Shape shape : SHAPES) {
            try {
                InstagramAnimatedContract candidate = new InstagramAnimatedContract(loader, shape);
                if (found != null) return null;
                found = candidate;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        }
        return found;
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
        path.set(pending, gif.file.getAbsolutePath());
        width.setInt(pending, gif.width); height.setInt(pending, gif.height);
        dimensions.invoke(pending, gif.width, gif.height);
        if (aspect == null) aspectField.setFloat(pending, (float) gif.width / gif.height);
        else aspect.invoke(pending, (float) gif.width / gif.height);
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
