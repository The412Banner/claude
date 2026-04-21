package claude.chat;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class TerminalActivity extends AppCompatActivity {

    private TextView outputView;
    private EditText inputView;
    private ScrollView scrollView;
    private Button connectToggleBtn;
    private Handler mainHandler;

    private ClaudeSessionService service;
    private boolean bound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ClaudeSessionService.SessionBinder) binder).getService();
            bound = true;
            service.setListener(text -> mainHandler.post(() -> {
                outputView.append(text);
                scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
                updateToggleButton();
            }));
            updateToggleButton();
            if (!service.connected) {
                SharedPreferences prefs = getSharedPreferences("claude_prefs", MODE_PRIVATE);
                service.connect(prefs.getString("api_key", ""));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            updateToggleButton();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        outputView = findViewById(R.id.terminal_output);
        inputView = findViewById(R.id.terminal_input);
        scrollView = findViewById(R.id.terminal_scroll);
        connectToggleBtn = findViewById(R.id.btn_connect_toggle);
        ImageButton sendBtn = findViewById(R.id.send_btn);
        mainHandler = new Handler(Looper.getMainLooper());

        sendBtn.setOnClickListener(v -> sendInput());

        findViewById(R.id.key_esc).setOnClickListener(v -> sendRaw(""));
        findViewById(R.id.key_tab).setOnClickListener(v -> sendRaw("\t"));
        findViewById(R.id.key_up).setOnClickListener(v -> sendRaw("[A"));
        findViewById(R.id.key_down).setOnClickListener(v -> sendRaw("[B"));
        findViewById(R.id.key_left).setOnClickListener(v -> sendRaw("[D"));
        findViewById(R.id.key_right).setOnClickListener(v -> sendRaw("[C"));
        findViewById(R.id.key_enter).setOnClickListener(v -> sendRaw("\r"));

        connectToggleBtn.setOnClickListener(v -> {
            if (!bound || service == null) return;
            if (service.connected) {
                service.disconnect();
            } else {
                outputView.setText("");
                service.reconnect();
            }
            updateToggleButton();
        });

        inputView.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendInput();
                return true;
            }
            return false;
        });
    }

    private void updateToggleButton() {
        if (connectToggleBtn == null) return;
        mainHandler.post(() -> {
            boolean isConnected = bound && service != null && service.connected;
            connectToggleBtn.setText(isConnected ? "DISCONNECT" : "RECONNECT");
            connectToggleBtn.setTextColor(isConnected ? 0xFFFF5252 : 0xFF4CAF50);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, ClaudeSessionService.class);
        startForegroundService(intent);
        bindService(intent, connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            service.clearListener();
            unbindService(connection);
            bound = false;
        }
    }

    private void sendRaw(String seq) {
        if (bound && service != null) service.sendRaw(seq);
    }

    private void sendInput() {
        if (inputView.getText().toString().isEmpty()) return;
        String text = inputView.getText().toString();
        inputView.setText("");
        outputView.append("> " + text + "\n");
        sendRaw(text + "\r");
    }
}
