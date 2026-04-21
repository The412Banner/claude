package claude.chat;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Base64;
import java.io.InputStream;

public class TermuxBridge {

    static final int BRIDGE_PORT = 9876;
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND";
    static final String BRIDGE_SCRIPT_PATH = "/data/data/com.termux/files/home/claude_bridge.py";

    public static boolean isTermuxInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // Install script only (used from setup UI)
    public static String installBridgeScript(Context ctx, String apiKey) {
        String b64 = readScriptAsBase64(ctx);
        if (b64 == null) return "Failed to read bridge script from assets";
        String cmd = "echo '" + b64 + "' | base64 -d > " + BRIDGE_SCRIPT_PATH + " && chmod +x " + BRIDGE_SCRIPT_PATH;
        return runInTermux(ctx, new String[]{"/data/data/com.termux/files/usr/bin/bash", "-c", cmd}, false);
    }

    // Single command: write script via base64, kill old bridge, start fresh — all in one intent
    public static String startBridge(Context ctx, String apiKey) {
        String b64 = readScriptAsBase64(ctx);
        if (b64 == null) return "Failed to read bridge script from assets";
        String cmd = "echo '" + b64 + "' | base64 -d > " + BRIDGE_SCRIPT_PATH
                + " && chmod +x " + BRIDGE_SCRIPT_PATH
                + "; pkill -f claude_bridge.py; sleep 0.5"
                + "; python3 " + BRIDGE_SCRIPT_PATH + " " + apiKey + " &";
        return runInTermux(ctx, new String[]{"/data/data/com.termux/files/usr/bin/bash", "-c", cmd}, false);
    }

    private static String readScriptAsBase64(Context ctx) {
        try {
            InputStream is = ctx.getAssets().open("claude_bridge.py");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            return Base64.encodeToString(buffer, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public static String runInTermux(Context ctx, String[] args, boolean openTermux) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, TERMUX_PACKAGE + ".app.RunCommandService");
        intent.setAction(TERMUX_RUN_COMMAND);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", args[0]);
        if (args.length > 1) {
            String[] extraArgs = new String[args.length - 1];
            System.arraycopy(args, 1, extraArgs, 0, extraArgs.length);
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", extraArgs);
        }
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", !openTermux);
        try {
            ctx.startForegroundService(intent);
            return null;
        } catch (SecurityException e) {
            return "permission_denied";
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
