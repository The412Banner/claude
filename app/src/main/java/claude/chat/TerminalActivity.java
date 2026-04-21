package claude.chat;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TerminalActivity extends AppCompatActivity {

    private TextView outputView;
    private EditText inputView;
    private ScrollView scrollView;
    private Handler mainHandler;
    private ExecutorService executor;

    private Socket socket;
    private OutputStream outputStream;
    private boolean connected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        outputView = findViewById(R.id.terminal_output);
        inputView = findViewById(R.id.terminal_input);
        scrollView = findViewById(R.id.terminal_scroll);
        ImageButton sendBtn = findViewById(R.id.send_btn);

        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newCachedThreadPool();

        SharedPreferences prefs = getSharedPreferences("claude_prefs", MODE_PRIVATE);
        String apiKey = prefs.getString("api_key", "");

        TermuxBridge.startBridge(this, apiKey);
        appendOutput("Starting bridge...\n");
        executor.execute(this::connectWithRetry);

        sendBtn.setOnClickListener(v -> sendInput());
        inputView.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendInput();
                return true;
            }
            return false;
        });
    }

    private void connectWithRetry() {
        int attempts = 0;
        while (attempts < 20 && !connected) {
            try {
                Thread.sleep(1000);
                socket = new Socket("127.0.0.1", TermuxBridge.BRIDGE_PORT);
                outputStream = socket.getOutputStream();
                connected = true;
                appendOutput("Connected.\n");
                readLoop();
            } catch (Exception e) {
                attempts++;
                appendOutput("Waiting for bridge (" + attempts + "/20)...\n");
            }
        }
        if (!connected) {
            appendOutput("\nCould not connect. Make sure claude is installed in Termux.\nRun: npm install -g @anthropic-ai/claude-code\n");
        }
    }

    private void readLoop() {
        try {
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                String raw = new String(buf, 0, n, StandardCharsets.UTF_8);
                String clean = stripAnsi(raw);
                if (!clean.isEmpty()) appendOutput(clean);
            }
        } catch (Exception e) {
            // ignore
        }
        appendOutput("\n[disconnected]\n");
        connected = false;
    }

    private String stripAnsi(String s) {
        // Remove ANSI escape sequences and carriage returns
        return s.replaceAll("\\[[;\\d]*[A-Za-z]", "")
                .replaceAll("\\][^]*", "")
                .replace("\r", "");
    }

    private void sendInput() {
        String text = inputView.getText().toString();
        if (text.isEmpty() || !connected) return;
        inputView.setText("");
        appendOutput("> " + text + "\n");
        executor.execute(() -> {
            try {
                if (outputStream != null) {
                    outputStream.write((text + "\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
            } catch (Exception ignored) {}
        });
    }

    private void appendOutput(String text) {
        mainHandler.post(() -> {
            outputView.append(text);
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }
}
