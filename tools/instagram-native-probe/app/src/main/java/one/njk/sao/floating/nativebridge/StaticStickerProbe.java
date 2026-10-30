package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import org.json.JSONObject;

/** Synthetic stills only; no authenticated uploader or sender is invoked. */
final class StaticStickerProbe {
    static void verify(Context context, InstagramStickerEncoder encoder,
            InstagramAnimatedContract contract, JSONObject report) throws Exception {
        File png = new File(context.getCacheDir(), "static-alpha.png");
        Bitmap pixels = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        try (FileOutputStream stream = new FileOutputStream(png)) {
            for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++)
                pixels.setPixel(x, y, (x < 20 ? 0 : x < 40 ? 0x80000000 : 0xff000000) | 0x003388dd);
            if (!pixels.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new AssertionError("PNG fixture failed");
        } finally { pixels.recycle(); }
        try { verifyStill(context, encoder, contract, png, "png", 64, 48, report); }
        finally { png.delete(); }

        File jpeg = asset(context, "sticker-oriented.jpg");
        try { verifyStill(context, encoder, contract, jpeg, "jpeg", 24, 32, report); }
        finally { jpeg.delete(); }

        File animation = asset(context, "floating/hello.gif");
        try {
            boolean rejected = false;
            try { File unexpected = encoder.convertImage(animation); unexpected.delete(); }
            catch (java.io.IOException expected) { rejected = true; }
            report.put("static_encoder_rejects_animation_instead_of_flattening", rejected);
        } finally { animation.delete(); }
    }

    private static File asset(Context context, String asset) throws Exception {
        File file = File.createTempFile("static-fixture-", ".input", context.getCacheDir());
        try (var input = context.getAssets().open(asset); var output = new FileOutputStream(file)) { input.transferTo(output); }
        return file;
    }

    private static Bitmap decode(File file) throws Exception {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setUnpremultipliedRequired(true);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
    }

    private static void verifyStill(Context context, InstagramStickerEncoder encoder,
            InstagramAnimatedContract contract, File source, String name, int width, int height,
            JSONObject report) throws Exception {
        byte[] original = Files.readAllBytes(source.toPath());
        File encoded = encoder.convertImage(source);
        try (InstagramAnimationFile staged = InstagramAnimationFile.prepareWebp(context, Uri.fromFile(encoded))) {
            report.put(name + "_sticker_original_dimensions", staged.width == width && staged.height == height);
            Bitmap expected = decode(source), actual = decode(staged.file);
            try {
                int[] before = new int[expected.getWidth() * expected.getHeight()], after = new int[before.length];
                expected.getPixels(before, 0, expected.getWidth(), 0, 0, expected.getWidth(), expected.getHeight());
                actual.getPixels(after, 0, actual.getWidth(), 0, 0, actual.getWidth(), actual.getHeight());
                report.put(name + "_sticker_decoded_pixels_lossless", java.util.Arrays.equals(before, after));
                if (!java.util.Arrays.equals(before, after)) {
                    for (int index = 0; index < before.length; index++) if (before[index] != after[index]) {
                        report.put(name + "_first_pixel_difference", Integer.toHexString(before[index]) + "/" + Integer.toHexString(after[index]));
                        break;
                    }
                    report.put(name + "_decoder_configs", expected.getConfig() + "/" + actual.getConfig());
                }
                if (name.equals("png")) report.put("static_sticker_preserves_alpha",
                    actual.hasAlpha() && (actual.getPixel(0, 0) >>> 24) == 0 &&
                    (actual.getPixel(30, 0) >>> 24) == 128 && (actual.getPixel(50, 0) >>> 24) == 255);
            } finally { expected.recycle(); actual.recycle(); }
            report.put(name + "_sticker_webp_pass_through_exact",
                java.util.Arrays.equals(Files.readAllBytes(encoded.toPath()), Files.readAllBytes(staged.file.toPath())));
            Object pending = contract.localAnimation(staged).pending();
            report.put(name + "_sticker_native_webp_upload_metadata",
                pending.getClass().getField("A4r").get(pending).equals("image/webp") &&
                pending.getClass().getField("A0I").getInt(pending) == 6 &&
                pending.getClass().getField("A6R").getBoolean(pending));
            report.put(name + "_sticker_source_unchanged", java.util.Arrays.equals(original, Files.readAllBytes(source.toPath())));
        } finally { encoded.delete(); }
    }
    private StaticStickerProbe() { }
}
