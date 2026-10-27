package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Validates animation without transcoding, decoding a full-size bitmap, or using a provider ID. */
final class InstagramGifFile implements AutoCloseable {
    final File file;
    final int width, height;
    private final InstagramMediaFile staged;

    private InstagramGifFile(InstagramMediaFile staged, int width, int height) {
        this.staged = staged; file = staged.file; this.width = width; this.height = height;
    }
    static InstagramGifFile prepare(Context context, Uri uri) throws IOException {
        return prepare(context, InstagramMediaFile.fromUri(context, uri));
    }
    // URI, local-file streams and existing byte buffers share this path. No byte arrays cross Binder.
    static InstagramGifFile prepare(Context context, InstagramMediaFile.Source source) throws IOException {
        InstagramMediaFile staged = InstagramMediaFile.prepare(context, source, ".gif", NativeVideoMetadata.MAX_BYTES);
        boolean valid = false;
        try {
            try (DataInputStream input = new DataInputStream(new FileInputStream(staged.file))) {
                byte[] signature = new byte[6];
                input.readFully(signature);
                String header = new String(signature, StandardCharsets.US_ASCII);
                if (!header.equals("GIF87a") && !header.equals("GIF89a")) throw new IOException("Not a GIF file");
            }
            int[] size = new int[2];
            Drawable decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(staged.file), (decoder, info, sourceInfo) -> {
                if (!"image/gif".equals(info.getMimeType())) throw new IllegalArgumentException("Not a GIF file");
                size[0] = info.getSize().getWidth(); size[1] = info.getSize().getHeight();
                // Only validate the animation; Instagram receives the unmodified original.
                decoder.setTargetSize(1, 1);
            });
            if (!(decoded instanceof AnimatedImageDrawable) || size[0] <= 0 || size[1] <= 0)
                throw new IOException("GIF does not contain an animation");
            valid = true;
            return new InstagramGifFile(staged, size[0], size[1]);
        } finally { if (!valid) staged.close(); }
    }
    void submitted() { staged.submitted(); }
    @Override public void close() { staged.close(); }
}
