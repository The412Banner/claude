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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TerminalActivity extends AppCompatActivity {

    private TextView outputView;
    private EditText inputView;
    private ScrollView scrollView;
    private Handler mainHandler;
    private ExecutorService executor;

    private Socket socket;
    private PrintWriter writer;
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

        // Launch bridge in Termux, then connect
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
                writer = new PrintWriter(socket.getOutputStream(), true);
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
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                final String output = line + "\n";
                appendOutput(output);
            }
        } catch (Exception e) {
            appendOutput("\n[disconnected]\n");
            connected = false;
        }
    }

    private void sendInput() {
        String text = inputView.getText().toString();
        if (text.isEmpty() || !connected) return;
        inputView.setText("");
        appendOutput("> " + text + "\n");
        executor.execute(() -> {
            if (writer != null) writer.println(text);
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
