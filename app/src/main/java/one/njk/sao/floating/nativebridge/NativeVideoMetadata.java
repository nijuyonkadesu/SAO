package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Looper;
import java.io.IOException;

/** Metadata for an explicitly granted video, read on the Binder worker before rechecking its draft. */
final class NativeVideoMetadata {
    static final long MAX_BYTES = 512L * 1024 * 1024;
    final int width, height, durationMs, rotation;
    private NativeVideoMetadata(int width, int height, int durationMs, int rotation) {
        this.width = width; this.height = height; this.durationMs = durationMs; this.rotation = rotation;
    }
    static boolean supports(String mime) { return "video/mp4".equals(mime) || "video/webm".equals(mime); }
    static NativeVideoMetadata read(Context context, Uri uri, String mime) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Video metadata must run on IO");
        if (!supports(mime)) throw new IOException("Unsupported video type");
        try (var descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor == null || descriptor.getLength() == 0 || descriptor.getLength() > MAX_BYTES)
                throw new IOException("Video size is unsupported");
        }
        try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
            metadata.setDataSource(context, uri);
            if (!"yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)) ||
                !mime.equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)))
                throw new IOException("Video container does not match its MIME type");
            int width = positive(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = positive(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int duration = positive(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            String rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return new NativeVideoMetadata(width, height, duration, rotation == null ? 0 : Integer.parseInt(rotation));
        }
    }
    private static int positive(String value) throws IOException {
        try { int number = Integer.parseInt(value); if (number > 0) return number; }
        catch (NumberFormatException ignored) { }
        throw new IOException("Video metadata unavailable");
    }
}
