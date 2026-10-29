package one.njk.sao.floating.nativebridge;

/** Preparation had no send side effect and the host already presented specific feedback. */
final class NativePreparationException extends Exception {
    NativePreparationException(Throwable cause) { super("Native media preparation failed", cause); }
}
