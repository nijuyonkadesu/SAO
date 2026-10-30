package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded, request-owned GIF/WebP with independently validated bytes, MIME and dimensions. */
final class InstagramAnimationFile implements AutoCloseable {
    final File file;
    final int width, height;
    final String mimeType;
    private final InstagramMediaFile staged;

    private InstagramAnimationFile(InstagramMediaFile staged, int width, int height, String mimeType) {
        this.staged = staged; file = staged.file; this.width = width; this.height = height; this.mimeType = mimeType;
    }
    static InstagramAnimationFile prepareGif(Context context, Uri uri) throws IOException {
        return prepareGif(context, InstagramMediaFile.fromUri(context, uri));
    }
    // URI, local-file streams and existing byte buffers share this path. No byte arrays cross Binder.
    static InstagramAnimationFile prepareGif(Context context, InstagramMediaFile.Source source) throws IOException {
        return prepare(context, source, "image/gif", ".gif");
    }
    static InstagramAnimationFile prepareWebp(Context context, File file) throws IOException {
        return prepare(context, () -> new FileInputStream(file), "image/webp", ".webp");
    }
    static InstagramAnimationFile prepareWebp(Context context, Uri uri) throws IOException {
        return prepare(context, InstagramMediaFile.fromUri(context, uri), "image/webp", ".webp");
    }
    private static InstagramAnimationFile prepare(Context context, InstagramMediaFile.Source source, String mimeType, String extension) throws IOException {
        InstagramMediaFile staged = InstagramMediaFile.prepare(context, source, extension, NativeVideoMetadata.MAX_BYTES);
        boolean valid = false;
        try {
            try (DataInputStream input = new DataInputStream(new FileInputStream(staged.file))) {
                byte[] signature = new byte[mimeType.equals("image/gif") ? 6 : 12];
                input.readFully(signature);
                String header = new String(signature, StandardCharsets.US_ASCII);
                if (mimeType.equals("image/gif")) {
                    if (!header.equals("GIF87a") && !header.equals("GIF89a")) throw new IOException("Not a GIF file");
                } else if (!header.startsWith("RIFF") || !header.endsWith("WEBP")) throw new IOException("Not a WebP file");
            }
            int[] size = new int[2];
            Drawable decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(staged.file), (decoder, info, sourceInfo) -> {
                if (!mimeType.equals(info.getMimeType())) throw new IllegalArgumentException("Animation MIME does not match bytes");
                size[0] = info.getSize().getWidth(); size[1] = info.getSize().getHeight();
                // Metadata/decodability only; no full-size bitmap allocation here.
                decoder.setTargetSize(1, 1);
            });
            // An encoder may losslessly merge a still clip into one GIF frame. Android
            // exposes that valid image/gif as BitmapDrawable, not AnimatedImageDrawable.
            if (!(decoded instanceof AnimatedImageDrawable || decoded instanceof BitmapDrawable) || size[0] <= 0 || size[1] <= 0)
                throw new IOException("Animation could not be decoded");
            valid = true;
            return new InstagramAnimationFile(staged, size[0], size[1], mimeType);
        } finally { if (!valid) staged.close(); }
    }
    void submitted() { staged.submitted(); }
    @Override public void close() { staged.close(); }
}
