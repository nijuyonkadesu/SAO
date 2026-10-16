package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Instagram's video importer needs a local file, not another app's FileProvider path. */
final class InstagramVideoFile implements AutoCloseable {
    static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024;
    final File file;
    final int width, height, durationMs, rotation;
    private volatile boolean submitted;

    private InstagramVideoFile(File file, int width, int height, int durationMs, int rotation) {
        this.file = file; this.width = width; this.height = height;
        this.durationMs = durationMs; this.rotation = rotation;
    }
    static InstagramVideoFile prepare(Context context, Uri uri) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Video preparation must run on IO");
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
        File file = File.createTempFile("clip-", ".mp4", directory);
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
            try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
                metadata.setDataSource(file.getAbsolutePath());
                if (!"yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)) ||
                    !"video/mp4".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)))
                    throw new IOException("Unsupported video container");
                int width = positive(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int height = positive(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                int duration = positive(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                String rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                return new InstagramVideoFile(file, width, height, duration, rotation == null ? 0 : Integer.parseInt(rotation));
            }
        } catch (Exception error) {
            file.delete();
            if (error instanceof IOException) throw error;
            throw new IOException("Could not read video metadata", error);
        }
    }
    private static int positive(String value) throws IOException {
        try { int number = Integer.parseInt(value); if (number > 0) return number; }
        catch (NumberFormatException ignored) { }
        throw new IOException("Video metadata unavailable");
    }
    void submitted() { submitted = true; }
    @Override public void close() { if (!submitted) file.delete(); }
}
