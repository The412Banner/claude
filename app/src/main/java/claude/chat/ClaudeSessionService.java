package claude.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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

    public void connect(String apiKey) {
        if (connected) return;
        executor.execute(() -> connectWithRetry(apiKey));
    }

    private void connectWithRetry(String apiKey) {
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
            } catch (Exception e) {
                attempts++;
                emit("Waiting for bridge (" + attempts + "/20)...\n");
            }
        }
        if (!connected) {
            emit("\nCould not connect. Make sure claude is installed in Termux.\n");
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
        // Keep buffer from growing unbounded
        if (outputBuffer.length() > 50000) {
            outputBuffer.delete(0, outputBuffer.length() - 50000);
        }
        if (listener != null) listener.onOutput(text);
    }

    private String stripAnsi(String s) {
        return s.replaceAll("\\[[;\\d]*[A-Za-z]", "")
                .replaceAll("\\][^]*", "")
                .replace("\r", "");
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
