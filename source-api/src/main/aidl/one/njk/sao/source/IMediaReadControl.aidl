package one.njk.sao.source;

/** Per-open cancellation capability. Valid only for the UID that opened the descriptor. */
oneway interface IMediaReadControl {
    void cancel();
}
