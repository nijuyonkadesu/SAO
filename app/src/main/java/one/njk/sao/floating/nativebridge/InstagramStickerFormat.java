package one.njk.sao.floating.nativebridge;

/** Advertises an operation, not a codec allowlist. The granted bytes determine their decoder. */
public final class InstagramStickerFormat {
    public static final String RENDITION = "application/vnd.sao.instagram-sticker";
    public static boolean isRendition(String mime) { return RENDITION.equals(mime); }
    static String[] renditions() { return new String[]{RENDITION}; }
    private InstagramStickerFormat() { }
}
