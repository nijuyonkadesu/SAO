package one.njk.sao.instagramprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("Testing Instagram's native composer against synthetic local fixtures. No Instagram account is used.");
        setContentView(view);
    }
}
