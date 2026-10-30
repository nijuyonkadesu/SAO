package one.njk.sao.floating.nativebridge;

import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Request-scoped native upload events; no polling or speculative message dispatch. */
final class InstagramStickerUpload {
    private final Class<?> listenerType;
    private final Method subscribe, unsubscribe, completed, successful, upload;
    private final Field key, attachment;

    InstagramStickerUpload(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> manager = loader.loadClass("com.instagram.pendingmedia.service.impl.PendingMediaManager");
        Class<?> pending = loader.loadClass("X.1jR");
        listenerType = loader.loadClass("X.Plp");
        subscribe = InstagramAnimatedContract.method(manager, "A0F", void.class, false, listenerType);
        unsubscribe = InstagramAnimatedContract.method(manager, "A0G", void.class, false, listenerType);
        upload = InstagramAnimatedContract.method(manager, "A0E", void.class, false, pending, boolean.class, boolean.class);
        completed = InstagramAnimatedContract.method(listenerType, "FKq", void.class, false, pending);
        if (listenerType.getMethods().length != 2 || completed.getReturnType() != void.class ||
            InstagramAnimatedContract.method(listenerType, "FaB", void.class, false, pending).getReturnType() != void.class)
            throw new NoSuchMethodException("GIF upload events changed");
        successful = InstagramAnimatedContract.method(pending, "A19", boolean.class, false);
        key = pending.getField("A4o"); attachment = pending.getField("A3O");
        if (successful.getReturnType() != boolean.class || key.getType() != String.class || attachment.getType() != Long.class)
            throw new NoSuchMethodException("GIF upload completion changed");
    }

    void start(Object manager, Object pending) throws ReflectiveOperationException {
        // This is uploadMedia, not postMedia: publishing/configuration is a separate
        // step owned by the recipient-bound animated command after FKq succeeds.
        upload.invoke(manager, pending, false, true);
    }

    AutoCloseable observe(Object manager, Object pending, Consumer<Object> uploaded, Runnable failed) throws ReflectiveOperationException {
        Handler main = new Handler(Looper.getMainLooper());
        Object expected = key.get(pending);
        AtomicBoolean finished = new AtomicBoolean();
        Object[] listener = new Object[1];
        Runnable timeout = () -> {
            if (!finished.compareAndSet(false, true)) return;
            detach(manager, listener[0]); failed.run();
        };
        listener[0] = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> "SAO GIF upload observer";
            };
            if (!method.equals(completed) || arguments == null || arguments.length != 1 ||
                !expected.equals(key.get(arguments[0]))) return null;
            Object result = arguments[0];
            main.post(() -> {
                if (!finished.compareAndSet(false, true)) return;
                main.removeCallbacks(timeout); detach(manager, listener[0]);
                try {
                    Long id = (Long) attachment.get(result);
                    if (Boolean.TRUE.equals(successful.invoke(result)) && id != null && id > 0) uploaded.accept(result);
                    else failed.run();
                } catch (ReflectiveOperationException | RuntimeException error) { failed.run(); }
            });
            return null;
        });
        subscribe.invoke(manager, listener[0]);
        main.postDelayed(timeout, 120_000);
        return () -> {
            if (finished.compareAndSet(false, true)) {
                main.removeCallbacks(timeout); detach(manager, listener[0]);
            }
        };
    }

    private void detach(Object manager, Object listener) {
        try { unsubscribe.invoke(manager, listener); }
        catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
}
