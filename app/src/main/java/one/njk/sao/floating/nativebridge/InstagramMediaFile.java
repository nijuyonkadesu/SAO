package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Request-owned, bounded staging for native importers that require a local file. */
final class InstagramMediaFile implements AutoCloseable {
    static final String DIRECTORY = "sao_dm_media";
    private static final long MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024;
    @FunctionalInterface interface Source { InputStream open() throws IOException; }
    final File file;
    private volatile boolean submitted;

    private InstagramMediaFile(File file) { this.file = file; }
    static Source fromUri(Context context, Uri uri) { return () -> context.getContentResolver().openInputStream(uri); }
    static InstagramMediaFile prepare(Context context, Source source, String extension, long maxBytes) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Media preparation must run on IO");
        File directory = new File(context.getCacheDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Media cache unavailable");
        long retained = 0;
        // Include the previous video-only cache in the budget and opportunistic cleanup.
        for (File root : new File[]{directory, new File(context.getCacheDir(), "sao_dm_video")}) {
            File[] files = root.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile()) continue;
                if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000L && file.delete()) continue;
                retained += file.length();
            }
        }
        long limit = Math.min(maxBytes, MAX_CACHE_BYTES - retained);
        if (limit <= 0) throw new IOException("Media cache is full");
        File file = File.createTempFile("media-", extension, directory);
        boolean complete = false;
        try {
            long started = SystemClock.elapsedRealtime(), total = 0;
            try (InputStream input = source.open(); FileOutputStream output = new FileOutputStream(file)) {
                if (input == null) throw new IOException("Media stream unavailable");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() - started > 30_000 || total > limit)
                        throw new IOException("Media preparation limit exceeded");
                    output.write(buffer, 0, count);
                }
            }
            if (total == 0) throw new IOException("Empty media stream");
            complete = true;
            return new InstagramMediaFile(file);
        } finally { if (!complete) file.delete(); }
    }
    void submitted() { submitted = true; }
    @Override public void close() { if (!submitted) file.delete(); }
}
