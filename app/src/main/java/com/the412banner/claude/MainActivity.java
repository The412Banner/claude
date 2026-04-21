package com.the412banner.claude;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("claude_prefs", MODE_PRIVATE);

        TextView termuxStatus = findViewById(R.id.termux_status);
        EditText apiKeyInput = findViewById(R.id.api_key_input);
        Button launchBtn = findViewById(R.id.launch_btn);
        Button installBridgeBtn = findViewById(R.id.install_bridge_btn);

        // Check Termux availability
        if (TermuxBridge.isTermuxInstalled(this)) {
            termuxStatus.setText("Termux: installed");
            termuxStatus.setTextColor(0xFF4CAF50);
        } else {
            termuxStatus.setText("Termux: not found — install Termux first");
            termuxStatus.setTextColor(0xFFFF5252);
            launchBtn.setEnabled(false);
            installBridgeBtn.setEnabled(false);
        }

        // Restore saved API key (masked)
        String savedKey = prefs.getString("api_key", "");
        if (!savedKey.isEmpty()) {
            apiKeyInput.setHint("API key saved");
        }

        installBridgeBtn.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            if (!key.isEmpty()) {
                prefs.edit().putString("api_key", key).apply();
            }
            TermuxBridge.installBridgeScript(this, prefs.getString("api_key", ""));
            termuxStatus.setText("Bridge script installing...");
        });

        launchBtn.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            if (!key.isEmpty()) {
                prefs.edit().putString("api_key", key).apply();
            }
            if (prefs.getString("api_key", "").isEmpty()) {
                apiKeyInput.setError("Enter your Anthropic API key first");
                return;
            }
            Intent intent = new Intent(this, TerminalActivity.class);
            startActivity(intent);
        });
    }
}
