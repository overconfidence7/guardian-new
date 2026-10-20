package app.guardianqa.stub;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Shows which app this stands in for. It does nothing else. */
public class StubActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setGravity(Gravity.CENTER);
        text.setTextSize(28);
        text.setPadding(48, 48, 48, 48);
        text.setText(getString(R.string.app_name) + "\n\nTest stand-in\n" + getPackageName());
        setContentView(text);
    }
}
