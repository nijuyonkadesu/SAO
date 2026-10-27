package one.njk.sao.floating.nativebridge;

/** Captured on Main after lease validation; executed on the Binder worker. */
@FunctionalInterface
interface NativeContentPreparation {
    PreparedNativeContent prepare() throws Exception;
}
