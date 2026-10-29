package one.njk.sao.floating.nativebridge;

/** Private bridge renditions; granted files keep their original MIME and bytes. */
public final class InstagramStickerFormat {
    public static final String GIF = "image/gif.igsticker";
    private static final String SUFFIX = ".igsticker";
    private static final java.util.List<String> INPUTS = supportedInputs();
    private static java.util.List<String> supportedInputs() {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (String mime : java.util.List.of("image/gif", "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif", "image/bmp",
                "video/mp4", "video/webm", "video/x-matroska", "video/quicktime", "video/3gpp")) {
            if (mime.startsWith("video/") || android.graphics.ImageDecoder.isMimeTypeSupported(mime)) values.add(mime);
        }
        return java.util.Collections.unmodifiableList(values);
    }
    public static String forInput(String mime) { return INPUTS.contains(mime) ? mime + SUFFIX : null; }
    public static String sourceMime(String rendition) {
        if (rendition == null || !rendition.endsWith(SUFFIX)) return null;
        String mime = rendition.substring(0, rendition.length() - SUFFIX.length());
        return INPUTS.contains(mime) ? mime : null;
    }
    static String[] renditions() { return INPUTS.stream().map(InstagramStickerFormat::forInput).toArray(String[]::new); }
    private InstagramStickerFormat() { }
}
