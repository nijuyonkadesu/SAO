package one.njk.sao.floating.nativebridge;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resolves module translations on IO; only the current explicit request can show a failure toast. */
final class InstagramInsertMessages {
    private final Context host;
    private final CharSequence failed, changed, memory, timeout, decode;
    private final AtomicBoolean shown = new AtomicBoolean();

    private InstagramInsertMessages(Context host, CharSequence failed, CharSequence changed, CharSequence memory, CharSequence timeout, CharSequence decode) {
        this.host = host.getApplicationContext(); this.failed = failed; this.changed = changed;
        this.memory = memory; this.timeout = timeout; this.decode = decode;
    }
    static InstagramInsertMessages load(Context host, Uri media) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Load feedback resources on IO");
        String authority = media.getAuthority();
        if (authority == null || !authority.endsWith(".fileprovider")) throw new IllegalArgumentException("Unknown module provider");
        String module = authority.substring(0, authority.length() - ".fileprovider".length());
        try {
            Context resources = host.createPackageContext(module, 0);
            return new InstagramInsertMessages(host, text(resources, module, "picker_instagram_sticker_failed"),
                text(resources, module, "picker_instagram_sticker_chat_changed"), text(resources, module, "picker_instagram_sticker_memory"),
                text(resources, module, "picker_instagram_sticker_timeout"), text(resources, module, "picker_instagram_sticker_decode_failed"));
        } catch (android.content.pm.PackageManager.NameNotFoundException | android.content.res.Resources.NotFoundException error) {
            return new InstagramInsertMessages(host, null, null, null, null, null);
        }
    }
    void failed() { show(failed); }
    void changed() { show(changed); }
    boolean preparationFailed(Throwable error) {
        CharSequence message = decode;
        String reason = "decode";
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof java.io.InterruptedIOException) return false;
            if (error instanceof InstagramStickerEncoder.LimitException limit) {
                reason = limit.limit.name(); message = limit.limit == InstagramStickerEncoder.Limit.MEMORY ? memory : timeout; break;
            }
            if (error instanceof one.njk.sao.floating.VideoFrameDecoder.DecodeException failure &&
                    failure.getReason() == one.njk.sao.floating.VideoFrameDecoder.Failure.MEMORY) {
                reason = "MEMORY"; message = memory; break;
            }
        }
        android.util.Log.w("SAO.InstagramGif", "sticker preparation failed; reason=" + reason);
        return show(message);
    }
    private static CharSequence text(Context resources, String module, String name) {
        return resources.getText(resources.getResources().getIdentifier(name, "string", module));
    }
    private boolean show(CharSequence message) {
        if (message == null || !shown.compareAndSet(false, true)) return false;
        return new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(host, message, Toast.LENGTH_LONG).show());
    }
}
