package one.njk.sao.source;
import one.njk.sao.source.ISourceCallback;
/** Append-only ABI. No credentials, file bytes or URLs in responses. */
interface ISourceExtension {
    String descriptor();
    oneway void request(String requestId, String profileId, String operation, String payload, ISourceCallback callback);
    oneway void cancel(String requestId);
}
