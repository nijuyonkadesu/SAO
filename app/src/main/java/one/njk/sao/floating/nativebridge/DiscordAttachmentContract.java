package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** The native React draft-file event, with video dimensions instead of an image-only receiver. */
final class DiscordAttachmentContract {
    private final Class<?> reactContext, payloadType;
    private final Constructor<?> imageEvent, dispatchedEvent;
    private final Method serialize, putInt, dispatcher, surfaceId, dispatch;

    private DiscordAttachmentContract(ClassLoader loader) throws ReflectiveOperationException {
        reactContext = loader.loadClass("com.facebook.react.bridge.ReactContext");
        payloadType = loader.loadClass("com.discord.reactevents.ReactEvent");
        Class<?> nativePayload = loader.loadClass("com.discord.chat.input.events.OnPasteImageEvent");
        Class<?> map = loader.loadClass("com.facebook.react.bridge.WritableMap");
        imageEvent = nativePayload.getConstructor(String.class, String.class);
        serialize = nativePayload.getMethod("serialize");
        if (!payloadType.isInterface() || !payloadType.isAssignableFrom(nativePayload) || serialize.getReturnType() != map)
            throw new NoSuchMethodException("Draft payload contract changed");
        putInt = map.getMethod("putInt", String.class, int.class);
        dispatchedEvent = loader.loadClass("com.discord.reactevents.DispatchedEvent")
            .getConstructor(int.class, int.class, String.class, payloadType);
        Class<?> helper = loader.loadClass("com.facebook.react.uimanager.UIManagerHelper");
        dispatcher = helper.getMethod("getEventDispatcher", reactContext);
        surfaceId = helper.getMethod("getSurfaceId", Context.class);
        Class<?> event = loader.loadClass("com.facebook.react.uimanager.events.Event");
        dispatch = loader.loadClass("com.facebook.react.uimanager.events.EventDispatcher").getMethod("dispatchEvent", event);
    }
    static DiscordAttachmentContract load(ClassLoader loader) {
        try { return new DiscordAttachmentContract(loader); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
    }
    private Object context(Context context) {
        for (int depth = 0; depth < 8 && context != null; depth++) {
            if (reactContext.isInstance(context)) return context;
            if (!(context instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }
    boolean supports(Context context) { return context(context) != null; }
    Object event(int surface, int tag, Uri uri, String mime, NativeVideoMetadata video) throws ReflectiveOperationException {
        boolean rotated = video.rotation == 90 || video.rotation == 270;
        Object payload = Proxy.newProxyInstance(payloadType.getClassLoader(), new Class<?>[]{payloadType}, (proxy, method, args) -> {
            if (method.getName().equals("serialize") && method.getParameterCount() == 0) {
                Object map = serialize.invoke(imageEvent.newInstance(uri.toString(), mime));
                putInt.invoke(map, "width", rotated ? video.height : video.width);
                putInt.invoke(map, "height", rotated ? video.width : video.height);
                return map;
            }
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> "SAO draft attachment";
            };
            throw new UnsupportedOperationException("Unknown draft payload method");
        });
        // The inspected manager registers onPasteImage. Its JS handler accepts a file URI,
        // real MIME and optional dimensions, then calls addFile for its own current channel.
        return dispatchedEvent.newInstance(surface, tag, "onPasteImage", payload);
    }
    boolean insert(View root, Uri uri, String mime, NativeVideoMetadata video) {
        Object context = context(root.getContext());
        if (context == null || root.getId() == View.NO_ID) return false;
        try {
            Object receiver = dispatcher.invoke(null, context);
            if (receiver == null) return false;
            Object event = event((int) surfaceId.invoke(null, context), root.getId(), uri, mime, video);
            dispatch.invoke(receiver, event);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return false; }
    }
}
