package one.njk.sao.discordprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("Testing Discord's native composer against synthetic local fixtures. No Discord account is used.");
        setContentView(view);
    }
}
