package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.IOException;

/** Instagram's video importer needs a local file, not another app's FileProvider path. */
final class InstagramVideoFile implements AutoCloseable {
    static final long MAX_BYTES = NativeVideoMetadata.MAX_BYTES;
    final File file;
    final int width, height, durationMs, rotation;
    private final InstagramMediaFile staged;

    private InstagramVideoFile(InstagramMediaFile staged, NativeVideoMetadata metadata) {
        this.staged = staged; file = staged.file;
        width = metadata.width; height = metadata.height;
        durationMs = metadata.durationMs; rotation = metadata.rotation;
    }
    static InstagramVideoFile prepare(Context context, Uri uri) throws Exception {
        return prepare(context, uri, "video/mp4");
    }
    static InstagramVideoFile prepare(Context context, Uri uri, String mime) throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) throw new IllegalStateException("Video preparation must run on IO");
        if (!NativeVideoMetadata.supports(mime)) throw new IOException("Unsupported video type");
        InstagramMediaFile staged = InstagramMediaFile.prepare(context, InstagramMediaFile.fromUri(context, uri),
            "video/webm".equals(mime) ? ".webm" : ".mp4", MAX_BYTES);
        try { return new InstagramVideoFile(staged, NativeVideoMetadata.read(context, Uri.fromFile(staged.file), mime)); }
        catch (Exception error) {
            staged.close();
            if (error instanceof IOException) throw error;
            throw new IOException("Could not read video metadata", error);
        } catch (LinkageError error) { staged.close(); throw error; }
    }
    void submitted() { staged.submitted(); }
    @Override public void close() { staged.close(); }
}
