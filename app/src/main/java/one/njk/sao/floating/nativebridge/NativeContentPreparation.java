package one.njk.sao.floating.nativebridge;

/** Captured on Main after lease validation; executed on the Binder worker. */
@FunctionalInterface
interface NativeContentPreparation {
    PreparedNativeContent prepare() throws Exception;
    /** True only when preparation captures an immutable native account/recipient before IO. */
    default boolean hasCapturedRecipient() { return false; }
}
