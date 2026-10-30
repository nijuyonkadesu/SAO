package one.njk.sao.floating.nativebridge;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import dalvik.system.DelegateLastClassLoader;
import java.io.Closeable;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Reads only a public SAO APK/resources and an owned synthetic fixture, under a foreign app UID. */
public final class ModuleLibraryProbe extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args; super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        File input = null;
        try {
            Context host = getTargetContext();
            Context module = host.createPackageContext("one.njk.sao", 0);
            Context resources = NativeModuleResources.mediaContext(host, Uri.parse("content://one.njk.sao.fileprovider/fixture"));
            if (!resources.getPackageName().equals(host.getPackageName()) || !resources.getCacheDir().equals(host.getCacheDir()))
                throw new AssertionError("Module resources must preserve foreign host storage/identity");
            for (String name : new String[]{"picker_instagram_sticker_failed", "picker_instagram_sticker_chat_changed",
                    "picker_instagram_sticker_memory", "picker_instagram_sticker_timeout", "picker_instagram_sticker_decode_failed"}) {
                int id = resources.getResources().getIdentifier(name, "string", module.getPackageName());
                if (id == 0 || resources.getText(id).length() == 0) throw new AssertionError("Missing native feedback resource");
            }
            for (String name : new String[]{"vertex_shader_transformation_es2", "vertex_shader_transformation_es3",
                    "fragment_shader_transformation_sdr_internal_es2", "fragment_shader_transformation_hdr_internal_es3",
                    "fragment_shader_transformation_external_yuv_es3"}) {
                int id = resources.getResources().getIdentifier(name, "raw", module.getPackageName());
                try (java.io.InputStream shader = resources.getResources().openRawResource(id)) {
                    if (shader.readAllBytes().length < 20) throw new AssertionError("Missing module shader");
                }
            }
            input = File.createTempFile("module-decoder-", ".mp4", host.getCacheDir());
            try (java.io.InputStream from = host.getAssets().open("odd-mpeg4.mp4");
                 java.io.OutputStream to = new java.io.FileOutputStream(input)) { from.transferTo(to); }
            // No native search path: exercise production module-library resolution in a foreign loader.
            ClassLoader child = new DelegateLastClassLoader(module.getApplicationInfo().sourceDir, null, host.getClassLoader());
            Class<?> codec = child.loadClass("one.njk.sao.media.NativeDecoder");
            Class<?> entry = child.loadClass(arguments.getString("entryClass"));
            Method open = entry.getDeclaredMethod(arguments.getString("entryMethod"), Context.class, File.class, long.class, BooleanSupplier.class);
            open.setAccessible(true);
            if (!Modifier.isStatic(open.getModifiers())) throw new AssertionError("Expected the mapped static decoder entry");
            Object decoded = open.invoke(null, resources, input, 1_000_000L, (BooleanSupplier) () -> false);
            try (Closeable close = (Closeable) decoded) {
                Field[] handles = Arrays.stream(codec.getDeclaredFields())
                    .filter(field -> field.getType() == long.class && !Modifier.isStatic(field.getModifiers())).toArray(Field[]::new);
                if (handles.length != 1) throw new AssertionError("Unexpected decoder handle layout");
                handles[0].setAccessible(true);
                long handle = handles[0].getLong(decoded);
                Method metadata = codec.getDeclaredMethod("nativeInfo", long.class);
                Method next = codec.getDeclaredMethod("nativeNext", long.class, ByteBuffer.class, long[].class);
                metadata.setAccessible(true); next.setAccessible(true);
                long[] info = (long[]) metadata.invoke(decoded, handle);
                if (info[0] != 357 || info[1] != 372) throw new AssertionError("Wrong fixture geometry");
                ByteBuffer pixels = ByteBuffer.allocateDirect(357 * 372 * 4);
                long[] timing = new long[2]; int frames = 0;
                while ((Boolean) next.invoke(decoded, handle, pixels, timing)) {
                    if ((pixels.get(0) & 255) < 220 || (pixels.get(3) & 255) != 255 || timing[0] != frames * 40_000L)
                        throw new AssertionError("Wrong decoded color, alpha or timestamp");
                    frames++;
                }
                if (frames != 10) throw new AssertionError("Wrong decoded frame count");
            }
            result.putString("report", new JSONObject().put("module_feedback_retained", true).put("module_shaders_retained", true)
                .put("foreign_host_identity_preserved", true).put("minified_module_decoder_loaded", true)
                .put("decoded_frames", 10).toString());
            finish(-1, result);
        } catch (Throwable error) {
            // Fixtures have no private data. Report types only; no filesystem paths or exception text.
            result.putString("failure", error.getClass().getName());
            for (int depth = 0; depth < 4 && error.getCause() != null; depth++) {
                error = error.getCause(); result.putString("cause" + depth, error.getClass().getName());
            }
            finish(0, result);
        } finally { if (input != null) input.delete(); }
    }
}
