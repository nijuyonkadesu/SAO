package one.njk.sao.floating.nativebridge;

import android.net.Uri;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Inspected gallery contract, enabled by its shape rather than an app version string. */
final class InstagramGalleryContract {
    private final Class<?> ownerType, senderType;
    private final Field senderField, widthField, heightField;
    private final Constructor<?> mediumConstructor, selectedConstructor;
    private final Constructor<?> senderConstructor;
    private final Field contextField, sessionField, delegateField, pipelineField, storeField;
    private final Field mediaRestricted;
    private final Method send;

    private InstagramGalleryContract(ClassLoader loader) throws ReflectiveOperationException {
        ownerType = loader.loadClass("X.01SB");
        senderType = loader.loadClass("X.01TL");
        Class<?> medium = loader.loadClass("com.instagram.common.gallery.Medium");
        Class<?> selected = loader.loadClass("X.0S1g");
        Class<?> pending = loader.loadClass("X.03oB");
        senderField = ownerType.getDeclaredField("A14");
        if (senderField.getType() != senderType || !Modifier.isFinal(senderField.getModifiers()))
            throw new NoSuchFieldException("Gallery ownership changed");
        senderField.setAccessible(true);
        mediaRestricted = loader.loadClass("X.01ZC").getField("A04");
        if (mediaRestricted.getType() != boolean.class) throw new NoSuchFieldException("Media permission shape changed");
        contextField = senderType.getField("A01"); sessionField = senderType.getField("A02");
        delegateField = senderType.getField("A03"); pipelineField = senderType.getField("A05"); storeField = senderType.getField("A06");
        senderConstructor = senderType.getConstructor(contextField.getType(), sessionField.getType(),
            delegateField.getType(), pipelineField.getType(), storeField.getType());
        mediumConstructor = medium.getConstructor(Uri.class, String.class, String.class,
            int.class, int.class, int.class, int.class, int.class, long.class, long.class, boolean.class);
        widthField = medium.getField("A0D"); heightField = medium.getField("A05");
        selectedConstructor = selected.getConstructor(medium, pending);
        Class<?>[] signature = {loader.loadClass("X.0GJS"), loader.loadClass("X.0Kg9"),
            Long.class, List.class, boolean.class, boolean.class};
        Method found = null;
        // DEX allows numeric class names which Java's hidden-API lint parser cannot model.
        // Match this third-party contract directly, keeping Android API checks enabled.
        for (Method candidate : senderType.getDeclaredMethods()) {
            if (!candidate.getName().equals("A0A") || candidate.getReturnType() != void.class ||
                Modifier.isStatic(candidate.getModifiers()) || !java.util.Arrays.equals(candidate.getParameterTypes(), signature)) continue;
            if (found != null) throw new NoSuchMethodException("Ambiguous gallery send shape");
            found = candidate;
        }
        if (found == null) throw new NoSuchMethodException("Gallery send shape changed");
        send = found;
        send.setAccessible(true);
        boolean videoCallback = false;
        Class<?>[] callbackSignature = {signature[0], signature[1],
            loader.loadClass("com.instagram.pendingmedia.model.ClipInfo"), pending,
            Long.class, String.class, String.class, int.class, boolean.class};
        for (Method callback : delegateField.getType().getMethods())
            if (callback.getName().equals("GUG") && callback.getReturnType() == void.class &&
                java.util.Arrays.equals(callback.getParameterTypes(), callbackSignature)) videoCallback = true;
        if (!videoCallback) throw new NoSuchMethodException("Gallery video callback changed");
    }
    static InstagramGalleryContract load(ClassLoader loader) {
        try { return new InstagramGalleryContract(loader); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
    }
    Object sender(Object owner) {
        if (!ownerType.isInstance(owner)) return null;
        try { Object sender = senderField.get(owner); return senderType.isInstance(sender) ? sender : null; }
        catch (IllegalAccessException | RuntimeException ignored) { return null; }
    }
    boolean acceptsMedia(Object listener) {
        try { return mediaRestricted.getDeclaringClass().isInstance(listener) && !mediaRestricted.getBoolean(listener); }
        catch (IllegalAccessException | RuntimeException ignored) { return false; }
    }
    Object selectedVideo(InstagramVideoFile video) throws ReflectiveOperationException {
        long timestamp = video.file.lastModified() / 1000;
        Object medium = mediumConstructor.newInstance(Uri.fromFile(video.file), video.file.getAbsolutePath(),
            video.file.getName(), 0, 3, 0, video.rotation, video.durationMs, timestamp, timestamp, false);
        widthField.setInt(medium, video.width); heightField.setInt(medium, video.height);
        return selectedConstructor.newInstance(medium, null);
    }
    Object guardedSender(Object original, BooleanSupplier valid) throws ReflectiveOperationException {
        Object delegate = delegateField.get(original);
        if (delegate == null) throw new IllegalStateException("Gallery delegate unavailable");
        java.util.concurrent.atomic.AtomicBoolean sent = new java.util.concurrent.atomic.AtomicBoolean();
        Object guarded = Proxy.newProxyInstance(delegateField.getType().getClassLoader(), new Class<?>[]{delegateField.getType()},
            (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> "SAO gallery delegate";
                };
                // Only the video callback is allowed to send. A fresh sender is private to
                // this request; Instagram's normal gallery/delegate is never replaced.
                if (method.getName().equals("GUG")) {
                    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper() ||
                        !sent.compareAndSet(false, true) || !valid.getAsBoolean()) return null;
                } else if (!method.getName().equals("EbO")) return null;
                try { return method.invoke(delegate, arguments); }
                catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
            });
        return senderConstructor.newInstance(contextField.get(original), sessionField.get(original), guarded,
            pipelineField.get(original), storeField.get(original));
    }
    void send(Object sender, Object selected, BooleanSupplier valid) throws ReflectiveOperationException {
        // Native gallery dispatch decides clip limits, processing, encryption and upload.
        // The sender belongs to the exact controller leased by SAO; no recipient is supplied.
        send.invoke(guardedSender(sender, valid), null, null, null, List.of(selected), false, false);
    }
}
