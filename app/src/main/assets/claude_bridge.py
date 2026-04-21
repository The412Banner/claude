#!/usr/bin/env python3
"""
Claude Bridge — runs inside Termux.
Starts `claude --output-format stream-json` in a PTY, parses the JSON stream,
and forwards only the actual assistant text over TCP localhost:9876.
"""
import os
import pty
import re
import socket
import subprocess
import threading
import sys
import json
import datetime

PORT = 9876
HOST = "127.0.0.1"
LOG = "/data/data/com.termux/files/home/bridge.log"
JSON_LOG = "/data/data/com.termux/files/home/bridge_json.log"

current_proc = None
current_proc_lock = threading.Lock()

ANSI_RE = re.compile(r'\x1B(?:\[[^@-~]*[@-~]|\][^\x07\x1B]*(?:\x07|\x1B\\)|.)')

def log(msg):
    with open(LOG, "a") as f:
        f.write(f"{datetime.datetime.now()} {msg}\n")

def log_json(line):
    with open(JSON_LOG, "a") as f:
        f.write(line + "\n")

def extract_text(line):
    """
    Parse one JSON line from --output-format stream-json.
    Returns the text string to display, or None if nothing to show.
    Raw line is always written to bridge_json.log for inspection.
    """
    line = ANSI_RE.sub('', line).strip()
    if not line:
        return None
    log_json(line)
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        return None

    t = obj.get("type", "")

    # Streaming API format: content_block_delta carries text chunks
    if t == "content_block_delta":
        delta = obj.get("delta", {})
        if delta.get("type") == "text_delta":
            return delta.get("text", "")

    # Non-streaming / result format: top-level "result" field
    if t == "result":
        return obj.get("result", "") + "\n"

    # Some builds emit {"type":"text","text":"..."} directly
    if t == "text":
        return obj.get("text", "")

    return None

def handle_session(conn, api_key):
    global current_proc
    env = os.environ.copy()
    if api_key:
        env["ANTHROPIC_API_KEY"] = api_key

    master_fd, slave_fd = pty.openpty()
    proc = subprocess.Popen(
        [
            "/data/data/com.termux/files/usr/bin/proot-distro", "login", "debian",
            "--user", "claude-user", "--", "claude",
            "--dangerously-skip-permissions",
            "--output-format", "stream-json",
        ],
        stdin=slave_fd,
        stdout=slave_fd,
        stderr=slave_fd,
        env=env,
        close_fds=True
    )
    os.close(slave_fd)

    with current_proc_lock:
        current_proc = proc

    log(f"claude spawned pid={proc.pid}")

    def forward_to_client():
        buf = b""
        while True:
            try:
                chunk = os.read(master_fd, 4096)
                if not chunk:
                    break
                buf += chunk
                # Process complete lines
                while b"\n" in buf:
                    line_bytes, buf = buf.split(b"\n", 1)
                    line = line_bytes.decode("utf-8", errors="replace")
                    text = extract_text(line)
                    if text:
                        conn.sendall(text.encode("utf-8"))
            except OSError:
                break
        # Flush remaining buffer
        if buf:
            line = buf.decode("utf-8", errors="replace")
            text = extract_text(line)
            if text:
                try:
                    conn.sendall(text.encode("utf-8"))
                except OSError:
                    pass
        conn.close()

    def forward_to_process():
        while True:
            try:
                data = conn.recv(4096)
                if not data:
                    break
                os.write(master_fd, data)
            except OSError:
                break

    t1 = threading.Thread(target=forward_to_client, daemon=True)
    t2 = threading.Thread(target=forward_to_process, daemon=True)
    t1.start()
    t2.start()
    proc.wait()
    log(f"claude exited pid={proc.pid}")
    with current_proc_lock:
        if current_proc is proc:
            current_proc = None
    try:
        os.close(master_fd)
    except OSError:
        pass

def main():
    safe_argv = [a if "sk-ant" not in a else "sk-ant-***" for a in sys.argv]
    log(f"bridge started pid={os.getpid()} argv={safe_argv}")
    api_key = os.environ.get("ANTHROPIC_API_KEY", "")
    if not api_key and len(sys.argv) > 1:
        api_key = sys.argv[1]

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind((HOST, PORT))
    except OSError as e:
        log(f"bind failed: {e}")
        sys.exit(0)
    server.listen(1)
    log(f"listening on {HOST}:{PORT}")

    while True:
        conn, addr = server.accept()
        log(f"client connected from {addr}")

        with current_proc_lock:
            if current_proc is not None:
                log(f"killing previous claude pid={current_proc.pid}")
                current_proc.terminate()

        t = threading.Thread(target=handle_session, args=(conn, api_key), daemon=True)
        t.start()

if __name__ == "__main__":
    main()
