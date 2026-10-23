package one.njk.sao.floating.nativebridge;

/** Unknown is terminal: a dispatch may have happened and must never be automatically replayed. */
public enum NativeInsertResult {
    REJECTED(0), ACCEPTED(1), UNKNOWN(2);
    public final int wireValue;
    NativeInsertResult(int wireValue) { this.wireValue = wireValue; }
    public static NativeInsertResult fromWire(int value) {
        if (value == REJECTED.wireValue) return REJECTED;
        if (value == ACCEPTED.wireValue) return ACCEPTED;
        return UNKNOWN;
    }
}
