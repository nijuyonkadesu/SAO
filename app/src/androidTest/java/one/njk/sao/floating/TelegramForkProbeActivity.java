package one.njk.sao.floating;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.BitmapFactory;
import android.provider.OpenableColumns;
import android.database.Cursor;
import java.io.InputStream;

/** Test-only Telegram-style receiver, running under the test APK's independent UID. */
public final class TelegramForkProbeActivity extends Activity {
    public static final String RESULT = "one.njk.sao.test.FORK_PROBE_RESULT";
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        if (uri == null) { finish(); return; }
        String authority = uri.getAuthority();
        String destination = authority.substring(0, authority.length() - ".fileprovider".length());
        Intent result = new Intent(RESULT).setPackage(destination).putExtra("received_package", getPackageName())
            .putExtra("mime", getIntent().getType());
        // Behavioral contract from pinned Telegram/Nagram X sources, not a live Telegram account:
        // LaunchActivity chooses its document route for non-image intent MIME; document preparation
        // adds sticker attributes to a small .webp. Inspect the real provider and decoded file.
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            int count = 0, size; byte[] buffer = new byte[8192];
            while ((size = stream.read(buffer)) != -1) count += size;
            result.putExtra("length", count);
            result.putExtra("provider_mime", getContentResolver().getType(uri));
            String name = "";
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            }
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            try (InputStream image = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(image, null, options); }
            boolean documentRoute = "application/octet-stream".equals(getIntent().getType());
            boolean sticker = documentRoute && name.endsWith(".webp") && "image/webp".equals(options.outMimeType)
                && options.outWidth > 0 && options.outWidth <= 800 && options.outHeight > 0 && options.outHeight <= 800;
            result.putExtra("classification", sticker ? "sticker_document" : "photo_or_file");
            result.putExtra("width", options.outWidth); result.putExtra("height", options.outHeight);
        } catch (Exception error) { result.putExtra("error", error.getClass().getSimpleName()); }
        sendBroadcast(result); finish();
    }
}
