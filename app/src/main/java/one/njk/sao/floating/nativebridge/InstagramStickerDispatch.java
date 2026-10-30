package one.njk.sao.floating.nativebridge;

import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-use ownership checks around the native GIF dispatcher; never replaces its send implementation. */
final class InstagramStickerDispatch {
    record Claim(boolean owned, Object pending) { }
    record Invocation(boolean owned, boolean rejected, Object callback) { }
    private static final Claim UNREGISTERED = new Claim(false, null);
    private static final Invocation UNHANDLED = new Invocation(false, false, null);
    private final class Ready {
        final Object session, pending;
        final String key;
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable expire;
        Ready(String id, Object session, Object pending, String key) {
            this.session = session; this.pending = pending; this.key = key;
            this.expire = () -> ready.remove(id, this);
        }
        void cancelExpiry() { main.removeCallbacks(expire); }
    }
    private static volatile InstagramStickerDispatch installed;
    private final ConcurrentHashMap<String, Ready> ready = new ConcurrentHashMap<>();
    private final Field clientContext, commandMedia, pendingKey, attachment, session, mediaId;
    private final Field errorCode, failureDomain, sendChannel, automaticRetry, manualRetry;
    private final Method entry, complete;
    private final Object invalidRequest;
    private final Class<?> commandType, callbackType;

    InstagramStickerDispatch(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> handler = loader.loadClass("X.5aw"), pending = loader.loadClass("X.1jR");
        Class<?> base = loader.loadClass("X.A1x"), media = loader.loadClass("X.Ys9");
        Class<?> callback = loader.loadClass("X.Wbb");
        callbackType = callback;
        commandType = loader.loadClass("X.5av");
        mediaId = field(media, "A0F", String.class);
        clientContext = field(base, "A05", String.class); commandMedia = field(commandType, "A01", media);
        pendingKey = field(pending, "A4o", String.class);
        attachment = field(pending, "A3O", Long.class);
        session = field(handler, "A00", loader.loadClass("com.instagram.common.session.UserSession"));
        entry = InstagramAnimatedContract.method(handler, "GiQ", void.class, false, loader.loadClass("X.2le"), callback, base);
        Class<?> error = loader.loadClass("X.LRk");
        errorCode = field(error, "A05", String.class);
        failureDomain = field(error, "A01", loader.loadClass("X.MVU")); sendChannel = field(error, "A07", String.class);
        automaticRetry = field(error, "A0D", boolean.class); manualRetry = field(error, "A0E", boolean.class);
        complete = InstagramAnimatedContract.method(callback, "FdI", void.class, false, error, String.class);
        Field invalid = error.getField("A0X");
        if (invalid.getType() != error || !Modifier.isStatic(invalid.getModifiers()))
            throw new NoSuchFieldException("GIF request failure changed");
        invalidRequest = invalid.get(null); // Stock invalid-mutation error; neither automatic nor manual retry.
        if (invalidRequest == null || automaticRetry.getBoolean(invalidRequest) || manualRetry.getBoolean(invalidRequest))
            throw new NoSuchFieldException("GIF request failure retry policy changed");
    }

    Method entryPoint() { return entry; }
    void installed() { installed = this; }
    static InstagramStickerDispatch forCommand(Class<?> command) {
        InstagramStickerDispatch current = installed;
        return current != null && current.commandType == command ? current : null;
    }

    AutoCloseable register(Object command, Object ownerSession, Object pending) throws ReflectiveOperationException {
        String id = (String) clientContext.get(command);
        String key = (String) pendingKey.get(pending);
        Long uploaded = (Long) attachment.get(pending);
        if (id == null || id.isBlank() || key == null || key.isBlank() || uploaded == null || uploaded <= 0 ||
            !uploaded.toString().equals(mediaId.get(commandMedia.get(command))))
            throw new IllegalStateException("Sticker configuration requires its completed upload");
        Ready value = new Ready(id, ownerSession, pending, uploaded.toString());
        if (ready.putIfAbsent(id, value) != null) throw new IllegalStateException("GIF request already registered");
        value.main.postDelayed(value.expire, 120_000);
        return () -> { ready.remove(id, value); value.cancelExpiry(); };
    }

    Invocation prepareInvocation(Object handler, Object callback, Object command) throws ReflectiveOperationException {
        Claim claimed = claim(handler, command);
        if (!claimed.owned()) return UNHANDLED;
        if (claimed.pending() == null) {
            android.util.Log.w("SAO.InstagramGif", "GIF request context changed; attachment configuration cancelled");
            // The native queue already owns this command. Complete its callback with
            // a failure rather than leaving the optimistic message in Sending forever.
            complete.invoke(callback, invalidRequest, null);
            return new Invocation(true, true, null);
        }
        if (InstagramAnimatedContract.diagnosticsEnabled())
            android.util.Log.i("SAO.InstagramGif", "native sticker dispatcher; route=riff; uploadedId=true");
        return new Invocation(true, false, observeCompletion(callback));
    }

    private Object observeCompletion(Object callback) {
        if (!InstagramAnimatedContract.diagnosticsEnabled()) return callback;
        AtomicBoolean reported = new AtomicBoolean();
        return Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> "SAO GIF completion observer";
            };
            if (method.equals(complete) && reported.compareAndSet(false, true)) {
                // Do not stringify native errors: they can contain request/media URLs.
                try {
                    Object error = arguments[0];
                    if (error == null) android.util.Log.i("SAO.InstagramGif", "attachment completion; nativeError=false");
                    else {
                        String code = (String) errorCode.get(error), channel = (String) sendChannel.get(error);
                        Object kind = failureDomain.get(error);
                        String domain = kind instanceof Enum<?> value ? value.name() : "other";
                        if (code == null || !code.matches("-?[0-9]{1,6}")) code = "other";
                        if (channel == null || !java.util.Set.of("http", "mqtt", "dgw", "ae", "na").contains(channel)) channel = "other";
                        android.util.Log.i("SAO.InstagramGif", "attachment completion; nativeError=true; code=" + code +
                            "; domain=" + domain + "; channel=" + channel + "; automaticRetry=" + automaticRetry.getBoolean(error) +
                            "; manualRetry=" + manualRetry.getBoolean(error));
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
            try { return method.invoke(callback, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }

    Claim claim(Object handler, Object command) throws ReflectiveOperationException {
        if (ready.isEmpty() || !commandType.isInstance(command)) return UNREGISTERED;
        String id = (String) clientContext.get(command);
        Ready value = id == null ? null : ready.get(id);
        if (value == null || !ready.remove(id, value)) return UNREGISTERED;
        value.cancelExpiry();
        if (session.get(handler) != value.session || !value.key.equals(mediaId.get(commandMedia.get(command))))
            return new Claim(true, null);
        return new Claim(true, value.pending);
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
        Field result = owner.getField(name);
        if (result.getType() != type || Modifier.isStatic(result.getModifiers()))
            throw new NoSuchFieldException("GIF attachment field changed");
        result.setAccessible(true);
        return result;
    }
}
