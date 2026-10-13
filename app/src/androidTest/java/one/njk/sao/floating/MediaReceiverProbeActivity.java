package one.njk.sao.floating;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import java.io.InputStream;
import java.security.MessageDigest;

/** A separate UID with only platform dependencies; the app classpath is intentionally absent. */
public final class MediaReceiverProbeActivity extends Activity {
    public static final String RESULT = "one.njk.sao.test.MEDIA_PROBE_RESULT";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent request = getIntent();
        Uri uri = Build.VERSION.SDK_INT >= 33
            ? request.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
            : request.getParcelableExtra(Intent.EXTRA_STREAM);
        Intent result = new Intent(RESULT).setPackage(request.getStringExtra("return_package"))
            .putExtra("nonce", request.getStringExtra("nonce"))
            .putExtra("outer_mime", request.getType())
            .putExtra("outer_action", request.getAction())
            .putExtra("receiver_uid", Process.myUid());
        try {
            if (uri == null) throw new IllegalArgumentException("No URI");
            result.putExtra("mime", getContentResolver().getType(uri));
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new IllegalArgumentException("No stream");
                int length = 0, count;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                while ((count = stream.read(buffer)) != -1) {
                    length += count;
                    digest.update(buffer, 0, count);
                }
                result.putExtra("length", length);
                StringBuilder hash = new StringBuilder();
                for (byte value : digest.digest()) hash.append(String.format("%02x", value & 0xff));
                result.putExtra("sha256", hash.toString());
            }
        } catch (Exception error) { result.putExtra("error", error.getClass().getSimpleName()); }
        sendBroadcast(result);
        finish();
    }
}
