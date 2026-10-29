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
    private static volatile String tracedId;
    private static volatile long traceUntil;
    private static volatile boolean diagnostics;
    static void enableDiagnostics() { diagnostics = true; }
    static boolean diagnosticsEnabled() { return diagnostics; }
    static boolean traces(Object pending) {
        String expected = tracedId;
        if (!diagnostics || expected == null || pending == null) return false;
        try {
            if (!expected.equals(pending.getClass().getField("A4o").get(pending))) return false;
            return android.os.SystemClock.elapsedRealtime() < traceUntil;
        }
        catch (ReflectiveOperationException ignored) { return false; }
    }
    private static void clearTrace(Object pending) {
        String expected = tracedId;
        if (expected == null) return;
        try {
            if (expected.equals(pending.getClass().getField("A4o").get(pending))) { tracedId = null; traceUntil = 0; }
        } catch (ReflectiveOperationException ignored) { }
    }
    private final Field sessionField, mediaRestricted, mediaType, shareType, path, width, height, aspectField, directUpload, modernUpload, legacyUpload;
    private final Field pendingStore, pendingFactory, mutationFactory;
    private final Constructor<?> pendingConstructor, requestConstructor;
    private final InstagramStickerCommand stickerCommand;
    private final InstagramStickerEncoder stickerEncoder;
    private final Field renderAsSticker, mime, format, directParams, riff, creationFlow;
    private final Method dimensions, aspect, metadata, pendingManager, register;
    private final Method threadContext, threadSequence, mutationManager, dispatch;
    private final Object animatedType, directShareType;
    private final InstagramStickerUpload gifUpload;
    private final InstagramStickerDispatch gifDispatch;

    private record Host(String owner, String session, String listener) { }
    private record Pending(String type, String mediaEnum, String mediaField, String shareField,
        String path, String dimensions, String aspect, String direct, String modern, String legacy) { }
    private record Upload(String policy, String factory, String store) { }
    private record Command(String metadata, String request, String context, String contextFactory,
        String type, String content, String extra, String options, String queue, String queueFactory,
        String base, String dispatch) { }
    private record Shape(Host host, Pending pending, Upload upload, Command command) { }
    // Select by complete field/method signatures, never the app's reported version.
    // The animated-sticker contract was inspected from the installed 447 APK.
    private static final Shape[] SHAPES = {
        new Shape(new Host("X.0q6", "A0t", "X.15C"),
            new Pending("X.1jR", "X.6A8", "A12", "A1s", "A4n", "A0U", null, "A74", "A6P", "A6L"),
            new Upload("X.5K2", "X.4gw", "X.0TW"),
            new Command("X.2lR", "X.7Uq", "X.Jg6", "X.Iy0", "X.5av", "X.0tT", "X.IyK", "X.OyD",
                "X.4sa", "X.4sd", "X.A1x", "A0E"))

    };

    private InstagramAnimatedContract(ClassLoader loader, Shape shape) throws ReflectiveOperationException {
        Class<?> session = loader.loadClass("com.instagram.common.session.UserSession");
        Class<?> key = loader.loadClass("com.instagram.model.direct.DirectThreadKey");
        Pending symbols = shape.pending();
        Command send = shape.command();
        Class<?> pending = loader.loadClass(symbols.type()), type = loader.loadClass(symbols.mediaEnum());
        gifUpload = symbols.type().equals("X.1jR") ? new InstagramStickerUpload(loader) : null;
        Class<?> share = loader.loadClass("com.instagram.pendingmedia.model.constants.ShareType");
        Class<?> descriptor = loader.loadClass(send.metadata()), request = loader.loadClass(send.request()), thread = loader.loadClass(send.context());
        Class<?> manager = loader.loadClass("com.instagram.pendingmedia.service.impl.PendingMediaManager");
        Class<?> store = loader.loadClass(shape.upload().store()), mutations = loader.loadClass(send.queue()), command = loader.loadClass(send.type());
        gifDispatch = InstagramStickerDispatch.forCommand(command);
        if (gifUpload == null || gifDispatch == null) throw new NoSuchMethodException("GIF attachment dispatch unavailable");
        stickerCommand = new InstagramStickerCommand(loader);
        stickerEncoder = new InstagramStickerEncoder(loader);
        renderAsSticker = field(pending, "A6R", boolean.class, false);
        mime = field(pending, "A4r", String.class, false);
        format = field(pending, "A0I", int.class, false);
        Class<?> params = loader.loadClass("com.instagram.pendingmedia.model.IGDirectParams");
        directParams = field(pending, "A1Q", params, false);
        riff = field(params, "A0F", boolean.class, false); creationFlow = field(params, "A05", String.class, false);
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
        metadata = method(descriptor, "A00", descriptor, true, pending);
        pendingFactory = field(manager, "A0E", loader.loadClass(shape.upload().factory()), true);
        pendingManager = method(pendingFactory.getType(), "A00", manager, false, Context.class, session);
        pendingStore = field(manager, "A0B", store, false);
        register = method(store, "A00", void.class, true, pending, store, boolean.class);
        threadContext = method(loader.loadClass(send.contextFactory()), "A00", thread, true, session, key);
        threadSequence = method(loader.loadClass(send.contextFactory()), "A01", Long.class, true, session, key);
        requestConstructor = request.getConstructor(thread, Boolean.class, String.class, String.class, String.class,
            boolean.class, boolean.class, boolean.class);
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
    LocalAnimation localAnimation(InstagramAnimationFile gif) throws ReflectiveOperationException {
        // Instagram's local-animation factory uses a decimal monotonic ID, not UUID text.
        Object pending = pendingConstructor.newInstance(Long.toString(System.nanoTime()));
        mediaType.set(pending, animatedType); shareType.set(pending, directShareType);
        path.set(pending, gif.file.getAbsolutePath());
        width.setInt(pending, gif.width); height.setInt(pending, gif.height);
        dimensions.invoke(pending, gif.width, gif.height);
        if (aspect == null) aspectField.setFloat(pending, (float) gif.width / gif.height);
        else aspect.invoke(pending, (float) gif.width / gif.height);
        directUpload.setBoolean(pending, true);
        renderAsSticker.setBoolean(pending, true); mime.set(pending, gif.mimeType);
        if (gif.mimeType.equals("image/webp")) format.setInt(pending, 6); // Native Riff uploader's WebP format.
        riff.setBoolean(directParams.get(pending), true); creationFlow.set(directParams.get(pending), "riff");
        return new LocalAnimation(pending, metadata.invoke(null, pending));
    }
    Object command(Object pending, Object key, Object request, Long sequence) throws ReflectiveOperationException {
        return stickerCommand.create(pending, key, request, sequence);
    }
    Object request(Object thread) throws ReflectiveOperationException {
        return requestConstructor.newInstance(thread, null, null, null, null, false, false, false);
    }
    PreparedNativeContent prepare(Context context, Uri uri, String sourceMime, Object session, Object key, BooleanSupplier valid) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Animation preparation must run on IO");
        InstagramInsertMessages messages = InstagramInsertMessages.load(context, uri);
        InstagramAnimationFile gif;
        try {
            if (sourceMime.startsWith("video/")) {
                try (InstagramMediaFile original = InstagramMediaFile.prepare(context,
                        InstagramMediaFile.fromUri(context, uri), ".video", NativeVideoMetadata.MAX_BYTES)) {
                    java.io.File webp = stickerEncoder.convertVideo(NativeModuleResources.mediaContext(context, uri), original.file);
                    try { gif = InstagramAnimationFile.prepareWebp(context, webp); }
                    finally { webp.delete(); }
                }
            } else try (InstagramAnimationFile original = InstagramAnimationFile.prepareGif(context, uri)) {
                java.io.File webp = stickerEncoder.convert(original.file);
                try { gif = InstagramAnimationFile.prepareWebp(context, webp); }
                finally { webp.delete(); }
            }
        } catch (Exception | LinkageError error) {
            if (messages.preparationFailed(error)) throw new NativePreparationException(error);
            throw error;
        }
        boolean prepared = false;
        try {
            LocalAnimation animation = localAnimation(gif);
            boolean modern = true; // Native Riff uploader selects this transport unconditionally.
            modernUpload.setBoolean(animation.pending(), modern); legacyUpload.setBoolean(animation.pending(), !modern);
            Object uploadManager = pendingManager.invoke(pendingFactory.get(null), context, session);
            Object queue = mutationManager.invoke(mutationFactory.get(null), session);
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
                    AutoCloseable observation = null;
                    try {
                        if (diagnostics) {
                            tracedId = (String) animation.pending().getClass().getField("A4o").get(animation.pending());
                            traceUntil = android.os.SystemClock.elapsedRealtime() + 120_000;
                            android.util.Log.i("SAO.InstagramGif", "dispatch; modern=" + modern);
                            android.util.Log.i("SAO.InstagramGif", "local file; exists=" + gif.file.exists() + "; readable=" + gif.file.canRead() + "; bytes=" + gif.file.length());
                        }
                        observation = gifUpload.observe(uploadManager, animation.pending(), uploaded -> {
                            clearTrace(animation.pending());
                            if (!valid.getAsBoolean()) { messages.changed(); return; }
                            try {
                                Object send = command(uploaded, key, request(threadContext.invoke(null, session, key)),
                                    (Long) threadSequence.invoke(null, session, key));
                                AutoCloseable registered = gifDispatch.register(send, session, uploaded);
                                boolean accepted;
                                try { accepted = Boolean.TRUE.equals(dispatch.invoke(queue, send)); }
                                catch (ReflectiveOperationException | RuntimeException error) {
                                    try { registered.close(); } catch (Exception ignored) { }
                                    throw error;
                                }
                                if (!accepted) try { registered.close(); } catch (Exception ignored) { }
                                if (!accepted) messages.failed();
                                android.util.Log.i("SAO.InstagramGif", "uploaded WebP; native configure/send dispatched=" + accepted);
                            } catch (ReflectiveOperationException | RuntimeException error) {
                                android.util.Log.w("SAO.InstagramGif", "Uploaded sticker could not be dispatched: " + error.getClass().getSimpleName());
                                messages.failed();
                            }
                        }, () -> {
                            clearTrace(animation.pending());
                            android.util.Log.w("SAO.InstagramGif", "GIF upload failed or timed out; no message dispatched"); messages.failed();
                        });
                        register.invoke(null, animation.pending(), store, true);
                        gifUpload.start(uploadManager, animation.pending());
                        return true;
                    } catch (Exception | LinkageError error) {
                        clearTrace(animation.pending());
                        if (observation != null) try { observation.close(); } catch (Exception closeError) { }
                        messages.failed();
                        throw new IllegalStateException("Native sticker upload outcome unknown", error);
                    }
                }
                @Override public void close() { gif.close(); }
            };
        } catch (Exception error) {
            if (diagnostics) android.util.Log.i("SAO.InstagramGif", "preparation failed=" + error.getClass().getSimpleName());
            throw error;
        } finally { if (!prepared) gif.close(); }
    }
    private static Field field(Class<?> owner, String name, Class<?> type, boolean isStatic) throws ReflectiveOperationException {
        Field field = owner.getField(name);
        if (field.getType() != type || Modifier.isStatic(field.getModifiers()) != isStatic)
            throw new NoSuchFieldException("Animated attachment field changed");
        return field;
    }
    static Method method(Class<?> owner, String name, Class<?> result, boolean isStatic, Class<?>... parameters) throws ReflectiveOperationException {
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
