package claude.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClaudeSessionService extends Service {

    private static final String CHANNEL_ID = "claude_session";
    private static final int NOTIF_ID = 1;

    private final IBinder binder = new SessionBinder();
    private ExecutorService executor = Executors.newCachedThreadPool();

    private Socket socket;
    private OutputStream outputStream;
    boolean connected = false;

    private OutputListener listener;
    private final StringBuilder outputBuffer = new StringBuilder();
    private String chatLogPath = null;

    public interface OutputListener {
        void onOutput(String text);
    }

    public class SessionBinder extends Binder {
        ClaudeSessionService getService() { return ClaudeSessionService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Claude session active"));
        return START_STICKY;
    }

    public void setListener(OutputListener l) {
        this.listener = l;
        // Replay buffered output to newly bound activity
        if (outputBuffer.length() > 0) {
            String buffered = outputBuffer.toString();
            if (l != null) l.onOutput(buffered);
        }
    }

    public void clearListener() {
        this.listener = null;
    }

    private boolean connecting = false;
    private String lastApiKey = "";

    public void connect(String apiKey) {
        if (connected || connecting) return;
        lastApiKey = apiKey;
        connecting = true;
        executor.execute(() -> connectWithRetry(apiKey));
    }

    public void disconnect() {
        connected = false;
        connecting = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        outputStream = null;
        emit("\n[disconnected]\n");
        updateNotification("Claude session active");
    }

    public void reconnect() {
        disconnect();
        outputBuffer.setLength(0);
        connect(lastApiKey);
    }

    private void connectWithRetry(String apiKey) {
        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        chatLogPath = "/data/data/com.termux/files/home/chat_" + ts + ".txt";
        appendLog("[session started " + ts + "]\n");
        emit("Starting bridge...\n");
        TermuxBridge.startBridge(this, apiKey);
        int attempts = 0;
        while (attempts < 20 && !connected) {
            try {
                Thread.sleep(1000);
                socket = new Socket("127.0.0.1", TermuxBridge.BRIDGE_PORT);
                outputStream = socket.getOutputStream();
                connected = true;
                emit("Connected.\n");
                updateNotification("Claude — connected");
                readLoop();
                break;
            } catch (Exception e) {
                attempts++;
                emit("Waiting for bridge (" + attempts + "/20)...\n");
            }
        }
        if (!connected) {
            emit("\nCould not connect. Make sure claude is installed in Termux.\n");
        }
        connecting = false;
    }

    private void readLoop() {
        try {
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                String raw = new String(buf, 0, n, StandardCharsets.UTF_8);
                String clean = stripAnsi(raw);
                if (!clean.isEmpty()) emit(clean);
            }
        } catch (Exception e) {
            // ignore
        }
        connected = false;
        emit("\n[disconnected]\n");
        updateNotification("Claude session active");
    }

    public void sendRaw(String seq) {
        if (!connected) return;
        String display = seq.replace("\r", "").replace("\n", "").trim();
        if (!display.isEmpty()) appendLog("[you] " + display + "\n");
        executor.execute(() -> {
            try {
                if (outputStream != null) {
                    outputStream.write(seq.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
            } catch (Exception ignored) {}
        });
    }

    private void emit(String text) {
        outputBuffer.append(text);
        if (outputBuffer.length() > 50000) {
            outputBuffer.delete(0, outputBuffer.length() - 50000);
        }
        if (!text.isBlank()) appendLog(text);
        if (listener != null) listener.onOutput(text);
    }

    private void appendLog(String text) {
        if (chatLogPath == null) return;
        executor.execute(() -> {
            try (FileWriter fw = new FileWriter(chatLogPath, true)) {
                fw.write(text);
            } catch (Exception ignored) {}
        });
    }

    private String stripAnsi(String s) {
        // CSI: ESC [ ... final-byte
        s = s.replaceAll("\\x1B\\[[^@-~]*[@-~]", "");
        // OSC: ESC ] ... BEL or ESC backslash
        s = s.replaceAll("\\x1B\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\)", "");
        // Other two-char ESC sequences and lone ESC
        s = s.replaceAll("\\x1B.", "").replaceAll("\\x1B", "");
        // Normalize CR
        s = s.replace("\r\n", "\n").replace("\r", "");
        // Drop lines that are only spinner/decoration chars
        s = s.replaceAll("(?m)^[\u2808-\u280F\u2810-\u281F\u2820-\u282F\u2830-\u283F✓✗·\\s]*$\n?", "");
        // Collapse 3+ consecutive blank lines to one blank line
        s = s.replaceAll("\n{3,}", "\n\n");
        return s;
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Claude Session", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent tap = new Intent(this, TerminalActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Claude CLI Chat")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}
