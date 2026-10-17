package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Instagram's video importer needs a local file, not another app's FileProvider path. */
final class InstagramVideoFile implements AutoCloseable {
    static final long MAX_BYTES = NativeVideoMetadata.MAX_BYTES;
    private static final long MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024;
    final File file;
    final int width, height, durationMs, rotation;
    private volatile boolean submitted;

    private InstagramVideoFile(File file, int width, int height, int durationMs, int rotation) {
        this.file = file; this.width = width; this.height = height;
        this.durationMs = durationMs; this.rotation = rotation;
    }
    static InstagramVideoFile prepare(Context context, Uri uri) throws Exception {
        return prepare(context, uri, "video/mp4");
    }
    static InstagramVideoFile prepare(Context context, Uri uri, String mime) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Video preparation must run on IO");
        if (!NativeVideoMetadata.supports(mime)) throw new IOException("Unsupported video type");
        File directory = new File(context.getCacheDir(), "sao_dm_video");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Video cache unavailable");
        long retained = 0;
        File[] cached = directory.listFiles();
        if (cached != null) for (File file : cached) {
            if (!file.isFile()) continue;
            // Cleanup happens only on explicit Paste. Never delete a recently submitted upload.
            if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000L && file.delete()) continue;
            retained += file.length();
        }
        long limit = Math.min(MAX_BYTES, MAX_CACHE_BYTES - retained);
        if (limit <= 0) throw new IOException("Video cache is full");
        File file = File.createTempFile("clip-", "video/webm".equals(mime) ? ".webm" : ".mp4", directory);
        try {
            long started = SystemClock.elapsedRealtime(), total = 0;
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(file)) {
                if (input == null) throw new IOException("Video stream unavailable");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() - started > 30_000 || total > limit)
                        throw new IOException("Video preparation limit exceeded");
                    output.write(buffer, 0, count);
                }
            }
            NativeVideoMetadata metadata = NativeVideoMetadata.read(context, Uri.fromFile(file), mime);
            return new InstagramVideoFile(file, metadata.width, metadata.height, metadata.durationMs, metadata.rotation);
        } catch (Exception error) {
            file.delete();
            if (error instanceof IOException) throw error;
            throw new IOException("Could not read video metadata", error);
        }
    }
    void submitted() { submitted = true; }
    @Override public void close() { if (!submitted) file.delete(); }
}
