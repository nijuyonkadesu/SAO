package one.njk.sao.floating.nativebridge;

import android.net.Uri;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/** Native Riff command configures one completed upload and sends its resulting sticker ID. */
final class InstagramStickerCommand {
    private final Constructor<?> command, media;
    private final Method recipients;
    private final Field request, requestId, clientContext, sequence, timestamp, recipient, content, localPath, attachment;

    InstagramStickerCommand(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = loader.loadClass("X.5av"), base = loader.loadClass("X.A1x"), thread = loader.loadClass("X.Ayf");
        Class<?> options = loader.loadClass("X.7Uq"), metadata = loader.loadClass("X.Ys9");
        command = type.getConstructor();
        Class<?>[] parameters = new Class<?>[23];
        parameters[0] = loader.loadClass("X.59h"); parameters[1] = loader.loadClass("X.1e6");
        parameters[2] = Double.class; parameters[3] = Double.class;
        Arrays.fill(parameters, 4, 18, String.class); Arrays.fill(parameters, 18, 21, List.class);
        parameters[21] = boolean.class; parameters[22] = boolean.class;
        media = metadata.getConstructor(parameters);
        recipients = InstagramAnimatedContract.method(thread, "A09", void.class, false, List.class);
        request = field(base, "A02", options); requestId = field(options, "A03", String.class);
        clientContext = field(base, "A05", String.class); sequence = field(thread, "A02", Long.class);
        timestamp = field(thread, "A00", long.class); recipient = field(type, "A02", loader.loadClass("com.instagram.model.direct.DirectThreadKey"));
        content = field(type, "A01", metadata); localPath = field(loader.loadClass("X.1jR"), "A4n", String.class);
        attachment = field(loader.loadClass("X.1jR"), "A3O", Long.class);
    }

    Object create(Object pending, Object key, Object options, Long nextSequence) throws ReflectiveOperationException {
        Object[] arguments = new Object[23];
        String path = (String) localPath.get(pending);
        Long upload = (Long) attachment.get(pending);
        if (path == null || path.isBlank() || upload == null || upload <= 0)
            throw new IllegalStateException("Sticker command requires a completed local upload");
        arguments[4] = path; arguments[5] = Uri.fromFile(new java.io.File(path)).toString();
        // Ys9.A0F is fed to I5o.A00 as data.media_id. It is NOT a registered
        // sticker ID: 5aw performs configuration before passing its result to Pz6.
        arguments[15] = upload.toString(); arguments[18] = List.of(); arguments[19] = List.of(); arguments[20] = List.of();
        arguments[21] = false; arguments[22] = false;
        Object result = command.newInstance();
        request.set(result, options); clientContext.set(result, requestId.get(options));
        recipients.invoke(result, List.of(key)); recipient.set(result, key); sequence.set(result, nextSequence);
        timestamp.setLong(result, System.currentTimeMillis() * 1000);
        content.set(result, media.newInstance(arguments));
        return result;
    }
    private static Field field(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
        Field result = owner.getDeclaredField(name);
        if (result.getType() != type || Modifier.isStatic(result.getModifiers())) throw new NoSuchFieldException("Sticker command changed");
        result.setAccessible(true); return result;
    }
}
