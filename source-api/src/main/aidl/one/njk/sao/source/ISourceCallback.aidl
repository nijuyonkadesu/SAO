package one.njk.sao.source;
oneway interface ISourceCallback {
    void complete(String requestId, String response);
    void failed(String requestId, String code);
}
