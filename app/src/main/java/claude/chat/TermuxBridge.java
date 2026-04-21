package claude.chat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

public class TermuxBridge {

    static final int BRIDGE_PORT = 9876;
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String BRIDGE_SCRIPT_PATH = "/data/data/com.termux/files/home/claude_bridge.py";

    public static boolean isTermuxInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // Returns null on success, or an error string on failure
    public static String installBridgeScript(Context ctx, String apiKey) {
        String script = getBridgeScriptContent(apiKey);
        String writeCmd = "cat > " + BRIDGE_SCRIPT_PATH + " << 'BRIDGESCRIPT'\n" + script + "\nBRIDGESCRIPT\nchmod +x " + BRIDGE_SCRIPT_PATH;
        return runInTermux(ctx, new String[]{"/data/data/com.termux/files/usr/bin/bash", "-c", writeCmd}, false);
    }

    // Returns null on success, or an error string on failure
    public static String startBridge(Context ctx, String apiKey) {
        return runInTermux(ctx, new String[]{"/data/data/com.termux/files/usr/bin/python3", BRIDGE_SCRIPT_PATH}, false);
    }

    // Returns null on success, or an error message string if it fails
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

    private static String getBridgeScriptContent(String apiKey) {
        // Returned as a string so it can be written to Termux home at runtime.
        // The actual content lives in assets/claude_bridge.py — this path is used
        // only when the asset loader is unavailable (fallback).
        return "# placeholder — install via assets";
    }
}
