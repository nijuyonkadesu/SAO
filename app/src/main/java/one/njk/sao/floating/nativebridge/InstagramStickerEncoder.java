package one.njk.sao.floating.nativebridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.ImageDecoder;
import android.graphics.ColorSpace;
import android.os.Looper;
import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import one.njk.sao.floating.VideoFrameDecoder;

/** Platform/native-app codecs; all frames and encoder resources belong to one explicit request. */
final class InstagramStickerEncoder {
    enum Limit { MEMORY, TIMEOUT }
    static final class LimitException extends IOException {
        final Limit limit;
        LimitException(Limit limit) { super(limit.name()); this.limit = limit; }
    }
    private final Constructor<?> encoder;
    private final Method createGif, frame, width, height, frameCount, durations, disposeGif;
    private final Method frameWidth, frameHeight, frameX, frameY, disposal, render, disposeFrame;
    private final Method prepare, add, assemble, reset;
    private final Field destroyed, hybrid;

    InstagramStickerEncoder(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> gif = loader.loadClass("com.facebook.animated.gif.GifImage");
        Class<?> gifFrame = loader.loadClass("com.facebook.animated.gif.GifFrame");
        Class<?> webp = loader.loadClass("com.facebook.onecamera.modules.videoencoding.webp.WebPEncoder");
        encoder = webp.getConstructor();
        createGif = method(gif, "createFromByteArray", gif, true, byte[].class);
        frame = gif.getDeclaredMethod("getFrame", int.class); frame.setAccessible(true);
        if (!frame.getReturnType().isAssignableFrom(gifFrame)) throw new NoSuchMethodException("GIF frame changed");
        width = method(gif, "getWidth", int.class, false); height = method(gif, "getHeight", int.class, false);
        frameCount = method(gif, "getFrameCount", int.class, false); durations = method(gif, "getFrameDurations", int[].class, false);
        disposeGif = method(gif, "nativeDispose", void.class, false);
        frameWidth = method(gifFrame, "getWidth", int.class, false); frameHeight = method(gifFrame, "getHeight", int.class, false);
        frameX = method(gifFrame, "getXOffset", int.class, false); frameY = method(gifFrame, "getYOffset", int.class, false);
        disposal = method(gifFrame, "getDisposalMode", int.class, false);
        render = method(gifFrame, "renderFrame", void.class, false, int.class, int.class, Bitmap.class);
        disposeFrame = method(gifFrame, "dispose", void.class, false);
        prepare = method(webp, "prepare", int.class, false, int.class, int.class, boolean.class);
        add = method(webp, "addFrame", int.class, false, ByteBuffer.class, int.class, int.class, int.class, int.class, int.class);
        assemble = method(webp, "assemble", ByteBuffer.class, false);
        destroyed = webp.getDeclaredField("mDestructed"); hybrid = webp.getDeclaredField("mHybridData");
        destroyed.setAccessible(true); hybrid.setAccessible(true);
        if (destroyed.getType() != AtomicBoolean.class || !hybrid.getType().getName().equals("com.facebook.jni.HybridData"))
            throw new NoSuchFieldException("WebP encoder lifetime changed");
        reset = method(hybrid.getType(), "resetNative", void.class, false);
    }

    @FunctionalInterface private interface Encode { void write(File output) throws Exception; }
    File convert(File input) throws IOException { return convert(input, output -> encode(input, output)); }
    File convertVideo(Context context, File input) throws IOException {
        return convert(input, output -> encodeVideo(context, input, output));
    }
    File convertImage(File input) throws IOException {
        return convert(input, output -> encodeImage(input, output));
    }
    private File convert(File input, Encode action) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Convert stickers on IO");
        File output = File.createTempFile("sao-sticker-", ".webp", input.getParentFile());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                action.write(output);
            } catch (Throwable error) { failure.set(error); }
        }, "SAO Instagram sticker encoder");
        worker.setDaemon(true); worker.start();
        boolean interrupted = false;
        try {
            try { worker.join(60_000); } catch (InterruptedException error) { interrupted = true; }
            if (interrupted || worker.isAlive()) {
                worker.interrupt();
                while (worker.isAlive()) try { worker.join(); } catch (InterruptedException error) { interrupted = true; }
                if (interrupted) throw new java.io.InterruptedIOException("Sticker encoding cancelled");
                throw new LimitException(Limit.TIMEOUT);
            }
            if (failure.get() != null) throw new IOException("Native sticker encoding failed", failure.get());
            return output;
        } catch (IOException error) { output.delete(); throw error; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void encodeImage(File input, File output) throws IOException {
        long began = System.nanoTime();
        Bitmap image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(input), (decoder, info, source) -> {
            if (info.isAnimated()) throw new IllegalArgumentException("Animated input requires its animation decoder");
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            if (w <= 0 || h <= 0 || w > 16383 || h > 16383) throw new IllegalArgumentException("Image dimensions are unsupported by WebP");
            if ((long) w * h > Runtime.getRuntime().maxMemory() / 64)
                throw new IllegalArgumentException("Image exceeds decode budget", new LimitException(Limit.MEMORY));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            // Encoding does not draw on Canvas. Keep straight alpha so decoding
            // and re-encoding do not round RGB through premultiplied pixels twice.
            decoder.setUnpremultipliedRequired(true);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Sticker encoding cancelled");
            // In lossless mode this is compression effort, not visual quality.
            // Keep the decoded pixels, alpha, EXIF orientation and original dimensions.
            if (!image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 75, stream)) throw new IOException("Static WebP encoding failed");
            if (InstagramAnimatedContract.diagnosticsEnabled()) android.util.Log.i("SAO.InstagramGif",
                "static WebP encoded; width=" + image.getWidth() + "; height=" + image.getHeight() + "; alpha=" + image.hasAlpha() +
                "; bytes=" + output.length() + "; elapsedMs=" + (System.nanoTime() - began) / 1_000_000);
        } finally { image.recycle(); }
    }

    private void encodeVideo(Context context, File input, File output) throws Exception {
        Object target = encoder.newInstance();
        ByteBuffer[] previous = new ByteBuffer[1];
        long[] previousMs = {0};
        int[] frames = {0};
        long began = System.nanoTime();
        try {
            // Reuse SAO's sequential MediaCodec + Media3 GL decoder. The input never
            // passes through an indexed-color GIF or gifski's quantization workers.
            VideoFrameDecoder.Result video = new VideoFrameDecoder(context).decode(input,
                kotlin.coroutines.EmptyCoroutineContext.INSTANCE, Runtime.getRuntime().maxMemory() / 64,
                (index, w, h, stride, rgba, ptsUs) -> {
                    try {
                        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Sticker encoding cancelled");
                        if (System.nanoTime() - began >= 60_000_000_000L) throw new LimitException(Limit.TIMEOUT);
                        long ms = Math.round(ptsUs / 1000.0);
                        if (previous[0] == null) {
                            if ((Integer) prepare.invoke(target, w, h, true) != 1) throw new IOException("Native WebP preparation failed");
                            previous[0] = ByteBuffer.allocateDirect(w * h * 4);
                        } else addFrame(target, previous[0], ms - previousMs[0]);
                        ByteBuffer from = rgba.duplicate(), to = previous[0];
                        int origin = from.position();
                        to.clear();
                        for (int row = 0; row < h; row++) {
                            from.limit(origin + row * stride + w * 4).position(origin + row * stride);
                            to.put(from);
                        }
                        to.flip(); previousMs[0] = ms; frames[0]++;
                        return kotlin.Unit.INSTANCE;
                    } catch (ReflectiveOperationException | IOException error) { throw new IllegalStateException("WebP frame encoding failed", error); }
                });
            if (previous[0] == null) throw new IOException("Video has no decodable frame");
            addFrame(target, previous[0], Math.round(video.getDurationUs() / 1000.0) - previousMs[0]);
            write(target, output);
            if (InstagramAnimatedContract.diagnosticsEnabled()) android.util.Log.i("SAO.InstagramGif",
                "direct video WebP encoded; frames=" + frames[0] + "; width=" + video.getWidth() + "; height=" + video.getHeight() +
                "; bytes=" + output.length() + "; elapsedMs=" + (System.nanoTime() - began) / 1_000_000);
        } finally { release(target); }
    }

    private void addFrame(Object target, ByteBuffer rgba, long durationMs) throws ReflectiveOperationException, IOException {
        rgba.rewind();
        if ((Integer) add.invoke(target, rgba, (int) Math.max(1, Math.min(Integer.MAX_VALUE, durationMs)), 0, 0, 0, 0) != 1)
            throw new IOException("Native WebP frame failed");
    }
    private void write(Object target, File output) throws ReflectiveOperationException, IOException {
        ByteBuffer bytes = (ByteBuffer) assemble.invoke(target);
        if (bytes == null || bytes.remaining() == 0 || bytes.remaining() > NativeVideoMetadata.MAX_BYTES)
            throw new IOException("Native WebP output unavailable");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            while (bytes.hasRemaining()) stream.getChannel().write(bytes);
        }
    }
    private void release(Object target) throws ReflectiveOperationException {
        if (target != null && ((AtomicBoolean) destroyed.get(target)).compareAndSet(false, true)) reset.invoke(hybrid.get(target));
    }

    private void encode(File input, File output) throws Exception {
        if (input.length() <= 0 || input.length() > Math.min(128L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4))
            throw new LimitException(Limit.MEMORY);
        Object image = null, target = null;
        Bitmap composite = null, previous = null, decoded = null;
        try {
            image = createGif.invoke(null, (Object) Files.readAllBytes(input.toPath()));
            int w = (Integer) width.invoke(image), h = (Integer) height.invoke(image), count = (Integer) frameCount.invoke(image);
            if (w <= 0 || h <= 0 || (long) w * h > Runtime.getRuntime().maxMemory() / 128 || count <= 0)
                throw new LimitException(Limit.MEMORY);
            int[] delays = (int[]) durations.invoke(image);
            if (delays.length != count) throw new IOException("Invalid GIF timing");
            target = encoder.newInstance();
            int initialized = (Integer) prepare.invoke(target, w, h, true);
            if (initialized != 1) throw new IOException("Native WebP preparation failed: " + initialized);
            composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(composite);
            ByteBuffer pixels = ByteBuffer.allocateDirect(composite.getByteCount());
            ByteBuffer packed = composite.getRowBytes() == w * 4 ? pixels : ByteBuffer.allocateDirect(w * h * 4);
            long deadline = System.nanoTime() + 60_000_000_000L;
            for (int index = 0; index < count; index++) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IOException("Sticker encoding cancelled");
                Object item = frame.invoke(image, index);
                try {
                    int fw = (Integer) frameWidth.invoke(item), fh = (Integer) frameHeight.invoke(item);
                    int x = (Integer) frameX.invoke(item), y = (Integer) frameY.invoke(item), mode = (Integer) disposal.invoke(item);
                    if (x < 0 || y < 0 || fw <= 0 || fh <= 0 || (long) x + fw > w || (long) y + fh > h)
                        throw new IOException("Invalid GIF frame bounds");
                    if (mode == 3) {
                        if (previous == null) previous = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        previous.eraseColor(Color.TRANSPARENT); new Canvas(previous).drawBitmap(composite, 0, 0, null);
                    }
                    if (decoded == null || decoded.getWidth() != fw || decoded.getHeight() != fh) {
                        if (decoded != null) decoded.recycle();
                        decoded = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
                    }
                    decoded.eraseColor(Color.TRANSPARENT); render.invoke(item, fw, fh, decoded);
                    canvas.drawBitmap(decoded, x, y, null);
                    pixels.clear(); composite.copyPixelsToBuffer(pixels);
                    if (packed != pixels) {
                        packed.clear();
                        for (int row = 0; row < h; row++) {
                            pixels.limit(row * composite.getRowBytes() + w * 4).position(row * composite.getRowBytes());
                            packed.put(pixels);
                        }
                    }
                    packed.rewind();
                    // Same full-bitmap call as Instagram's owX encoder worker: duration, then zero crop/offset arguments.
                    int added = (Integer) add.invoke(target, packed, Math.max(10, delays[index]), 0, 0, 0, 0);
                    if (added != 1) throw new IOException("Native WebP frame failed: " + added);
                    if (mode == 2) {
                        int save = canvas.save(); canvas.clipRect(x, y, x + fw, y + fh);
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR); canvas.restoreToCount(save);
                    } else if (mode == 3) {
                        composite.eraseColor(Color.TRANSPARENT); canvas.drawBitmap(previous, 0, 0, null);
                    }
                } finally { disposeFrame.invoke(item); }
            }
            write(target, output);
            if (InstagramAnimatedContract.diagnosticsEnabled()) android.util.Log.i("SAO.InstagramGif",
                "native WebP encoded; frames=" + count + "; width=" + w + "; height=" + h + "; bytes=" + output.length());
        } finally {
            if (decoded != null) decoded.recycle(); if (previous != null) previous.recycle(); if (composite != null) composite.recycle();
            try { if (image != null) disposeGif.invoke(image); }
            finally {
                release(target);
            }
        }
    }

    private static Method method(Class<?> type, String name, Class<?> result, boolean isStatic, Class<?>... arguments) throws ReflectiveOperationException {
        return InstagramAnimatedContract.method(type, name, result, isStatic, arguments);
    }
}
