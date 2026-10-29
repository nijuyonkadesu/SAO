package one.njk.sao.floating.nativebridge;

/** Private bridge renditions; granted files keep their original MIME and bytes. */
public final class InstagramStickerFormat {
    public static final String GIF = "image/gif.igsticker";
    private static final String SUFFIX = ".igsticker";
    private static final java.util.List<String> INPUTS = java.util.List.of(
        "image/gif", "video/mp4", "video/webm", "video/x-matroska", "video/quicktime", "video/3gpp");
    public static String forInput(String mime) { return INPUTS.contains(mime) ? mime + SUFFIX : null; }
    public static String sourceMime(String rendition) {
        if (rendition == null || !rendition.endsWith(SUFFIX)) return null;
        String mime = rendition.substring(0, rendition.length() - SUFFIX.length());
        return INPUTS.contains(mime) ? mime : null;
    }
    static String[] renditions() { return INPUTS.stream().map(InstagramStickerFormat::forInput).toArray(String[]::new); }
    private InstagramStickerFormat() { }
}
