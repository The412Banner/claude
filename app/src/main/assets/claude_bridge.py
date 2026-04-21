#!/usr/bin/env python3
"""
Claude Bridge — runs inside Termux.
Starts `claude` in a PTY and exposes it over a TCP socket on localhost:9876.
The Claude Android app connects to this socket to drive the session.
"""
import os
import pty
import socket
import subprocess
import threading
import sys

PORT = 9876
HOST = "127.0.0.1"

def bridge(conn, master_fd):
    def forward_to_client():
        while True:
            try:
                data = os.read(master_fd, 4096)
                if not data:
                    break
                conn.sendall(data)
            except OSError:
                break
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
    t1.join()
    t2.join()

def main():
    api_key = os.environ.get("ANTHROPIC_API_KEY", "")
    if not api_key and len(sys.argv) > 1:
        api_key = sys.argv[1]

    env = os.environ.copy()
    if api_key:
        env["ANTHROPIC_API_KEY"] = api_key

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(1)
    print(f"Claude bridge listening on {HOST}:{PORT}")

    while True:
        conn, addr = server.accept()
        print(f"Client connected: {addr}")

        master_fd, slave_fd = pty.openpty()
        proc = subprocess.Popen(
            ["claude"],
            stdin=slave_fd,
            stdout=slave_fd,
            stderr=slave_fd,
            env=env,
            close_fds=True
        )
        os.close(slave_fd)

        t = threading.Thread(target=bridge, args=(conn, master_fd), daemon=True)
        t.start()
        proc.wait()
        print("Claude process exited")

if __name__ == "__main__":
    main()
