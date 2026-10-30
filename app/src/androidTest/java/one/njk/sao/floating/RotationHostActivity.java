package one.njk.sao.floating;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Independent process/UID for overlay rotation tests, with no SAO classes on its classpath. */
public final class RotationHostActivity extends Activity {
    public static final String CLOSE = "one.njk.sao.test.CLOSE_ROTATION_HOST";
    private boolean showKeyboard;
    private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { finishAndRemoveTask(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closeReceiver, new IntentFilter(CLOSE), Context.RECEIVER_EXPORTED);
        else registerLegacyCloseReceiver();
        TextView label = new TextView(this);
        label.setText("Rotation test host");
        label.setGravity(Gravity.CENTER);
        showKeyboard = getIntent().getBooleanExtra("show_keyboard", false);
        if (showKeyboard) {
            LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
            content.addView(label, new LinearLayout.LayoutParams(-1, 0, 1));
            EditText editor = new EditText(this); editor.setHint("Synthetic test editor");
            content.addView(editor, new LinearLayout.LayoutParams(-1, -2));
            setContentView(content); editor.requestFocus();
        } else setContentView(label);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && showKeyboard && getWindow().getInsetsController() != null)
            getWindow().getInsetsController().show(android.view.WindowInsets.Type.ime());
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // API 30–32 predate export flags; this process uses only platform classes.
    private void registerLegacyCloseReceiver() {
        registerReceiver(closeReceiver, new IntentFilter(CLOSE));
    }

    @Override protected void onDestroy() {
        unregisterReceiver(closeReceiver);
        super.onDestroy();
    }
}
