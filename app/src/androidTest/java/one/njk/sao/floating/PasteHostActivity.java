package one.njk.sao.floating;

import android.app.Activity;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.io.InputStream;

/** Separate-UID media composer fixture. Stages an attachment, never sends a message. */
public final class PasteHostActivity extends Activity {
    public static final String COMMAND = "one.njk.sao.test.PASTE_COMMAND";
    public static final String STATE = "one.njk.sao.test.PASTE_STATE";
    private LinearLayout root;
    private Button sink;
    private Editor editor;
    private EditText other;
    private int pastes, previews, focuses, bytes;
    private String mime = "";
    private boolean reject, block, delayOnReturn, left;
    private long returned, pasted;
    private final BroadcastReceiver commands = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            switch (intent.getStringExtra("command")) {
                case "unfocus": editor.clearFocus(); sink.requestFocus(); break;
                case "delay": block = true; delayOnReturn = true; editor.clearFocus(); sink.requestFocus(); break;
                case "deny": block = true; editor.clearFocus(); sink.requestFocus(); break;
                case "replace": root.removeView(editor); editor = new Editor(); root.addView(editor); editor.requestFocus(); break;
                case "other": other.setVisibility(View.VISIBLE); other.requestFocus(); break;
                case "reject": reject = true; break;
                case "finish": finish(); break;
            }
            report();
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        sink = new Button(this); sink.setText("Focus sink"); sink.setFocusableInTouchMode(true);
        root.addView(sink);
        editor = new Editor(); root.addView(editor);
        other = new EditText(this); other.setHint("Different editor"); other.setVisibility(View.GONE); root.addView(other);
        boolean neverFocused = getIntent().getBooleanExtra("never_focused", false);
        editor.setFocusable(!neverFocused); editor.setFocusableInTouchMode(!neverFocused);
        setContentView(root); root.requestFocus();
        registerReceiver(commands, new IntentFilter(COMMAND), Context.RECEIVER_EXPORTED);
        root.post(() -> {
            if (neverFocused) sink.requestFocus();
            else editor.requestFocus();
            report();
        });
    }
    private final class Editor extends EditText {
        Editor() { super(PasteHostActivity.this); setHint("Original composer"); setContentDescription("Original composer"); }
        @Override public boolean requestFocus(int direction, Rect rect) {
            focuses++;
            return !block && super.requestFocus(direction, rect);
        }
        @Override public boolean onTextContextMenuItem(int id) {
            if (id != android.R.id.paste) return super.onTextContextMenuItem(id);
            pastes++; pasted = SystemClock.elapsedRealtime();
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            if (clip != null && clip.getItemAt(0).getUri() != null) {
                try (InputStream input = getContentResolver().openInputStream(clip.getItemAt(0).getUri())) {
                    bytes = 0; byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) != -1) bytes += count;
                    mime = getContentResolver().getType(clip.getItemAt(0).getUri()); previews++;
                } catch (Exception error) { mime = error.getClass().getSimpleName(); }
            }
            report();
            return !reject;
        }
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) left = true;
        if (focused && left && delayOnReturn) {
            delayOnReturn = false; returned = SystemClock.elapsedRealtime();
            editor.postDelayed(() -> { block = false; editor.requestFocus(); report(); }, 550);
        }
        if (editor != null) report();
    }
    private void report() {
        sendBroadcast(new Intent(STATE).setPackage(getIntent().getStringExtra("return_package"))
            .putExtra("pastes", pastes).putExtra("previews", previews).putExtra("focuses", focuses)
            .putExtra("focused", editor.hasFocus()).putExtra("window_focused", hasWindowFocus())
            .putExtra("bytes", bytes).putExtra("mime", mime)
            .putExtra("returned", returned).putExtra("pasted", pasted).putExtra("block", block));
    }
    @Override public void onDestroy() { unregisterReceiver(commands); super.onDestroy(); }
}
