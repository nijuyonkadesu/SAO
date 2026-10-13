package one.njk.sao.floating.nativebridge;
import one.njk.sao.floating.nativebridge.INativeStickerClient;

interface INativeBridge {
    void registerClient(String packageName, INativeStickerClient client);
}
