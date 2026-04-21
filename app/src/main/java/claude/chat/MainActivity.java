package claude.chat;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int PERM_REQUEST_INSTALL = 1001;
    private static final int PERM_REQUEST_LAUNCH = 1002;

    private SharedPreferences prefs;
    private TextView termuxStatus;
    private EditText apiKeyInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("claude_prefs", MODE_PRIVATE);

        termuxStatus = findViewById(R.id.termux_status);
        apiKeyInput = findViewById(R.id.api_key_input);
        Button launchBtn = findViewById(R.id.launch_btn);
        Button installBridgeBtn = findViewById(R.id.install_bridge_btn);

        if (TermuxBridge.isTermuxInstalled(this)) {
            termuxStatus.setText("Termux: installed");
            termuxStatus.setTextColor(0xFF4CAF50);
        } else {
            termuxStatus.setText("Termux: not found — install Termux first");
            termuxStatus.setTextColor(0xFFFF5252);
            launchBtn.setEnabled(false);
            installBridgeBtn.setEnabled(false);
        }

        String savedKey = prefs.getString("api_key", "");
        if (!savedKey.isEmpty()) {
            apiKeyInput.setHint("API key saved");
            if (getIntent().getBooleanExtra("launch_terminal", false)) {
                doLaunchBridge();
                return;
            }
        }

        installBridgeBtn.setOnClickListener(v -> {
            saveApiKey();
            if (!hasTermuxPermission()) {
                ActivityCompat.requestPermissions(this, new String[]{TERMUX_PERMISSION}, PERM_REQUEST_INSTALL);
            } else {
                doInstallBridge();
            }
        });

        launchBtn.setOnClickListener(v -> {
            saveApiKey();
            if (prefs.getString("api_key", "").isEmpty()) {
                apiKeyInput.setError("Enter your Anthropic API key first");
                return;
            }
            if (!hasTermuxPermission()) {
                ActivityCompat.requestPermissions(this, new String[]{TERMUX_PERMISSION}, PERM_REQUEST_LAUNCH);
            } else {
                doLaunchBridge();
            }
        });
    }

    private void saveApiKey() {
        String key = apiKeyInput.getText().toString().trim();
        if (!key.isEmpty()) {
            prefs.edit().putString("api_key", key).apply();
        }
    }

    private boolean hasTermuxPermission() {
        return ContextCompat.checkSelfPermission(this, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private void doInstallBridge() {
        String err = TermuxBridge.installBridgeScript(this, prefs.getString("api_key", ""));
        if (err == null) {
            termuxStatus.setText("Bridge script installing... check Termux");
            termuxStatus.setTextColor(0xFF4CAF50);
        } else {
            termuxStatus.setText("Error: " + err);
            termuxStatus.setTextColor(0xFFFF5252);
        }
    }

    private void doLaunchBridge() {
        String err = TermuxBridge.startBridge(this, prefs.getString("api_key", ""));
        if (err == null) {
            startActivity(new Intent(this, TerminalActivity.class));
        } else {
            termuxStatus.setText("Error: " + err);
            termuxStatus.setTextColor(0xFFFF5252);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            termuxStatus.setText("Permission denied.\n\nIn Termux, ensure termux.properties contains:\nallow-external-apps = true\n\nThen force-stop Termux, reopen it, and try again.");
            termuxStatus.setTextColor(0xFFFF9800);
            return;
        }
        if (requestCode == PERM_REQUEST_INSTALL) {
            doInstallBridge();
        } else if (requestCode == PERM_REQUEST_LAUNCH) {
            doLaunchBridge();
        }
    }
}
