#!/usr/bin/env python3
# Serves the bare repos under a folder over smart HTTP, pushes included, by handing each request to `git http-backend`.
# Usage: scripts/git-http-server.py <folder> <port>   (scripts/e2e.sh runs it)
import http.server, os, subprocess, sys

ROOT, PORT = sys.argv[1], int(sys.argv[2])

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass

    def do_GET(self): self.cgi()
    def do_POST(self): self.cgi()

    def cgi(self):
        path, _, query = self.path.partition("?")
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else (self.read_chunked() if self.headers.get("Transfer-Encoding") == "chunked" else b"")
        env = dict(os.environ, GIT_PROJECT_ROOT=ROOT, GIT_HTTP_EXPORT_ALL="1", PATH_INFO=path, QUERY_STRING=query,
                   REQUEST_METHOD=self.command, CONTENT_TYPE=self.headers.get("Content-Type", ""), CONTENT_LENGTH=str(len(body)),
                   REMOTE_USER="phone", REMOTE_ADDR=self.client_address[0], GIT_PROTOCOL=self.headers.get("Git-Protocol", ""),
                   HTTP_CONTENT_ENCODING=self.headers.get("Content-Encoding", ""))
        out = subprocess.run(["git", "http-backend"], input=body, env=env, capture_output=True).stdout
        head, _, rest = out.partition(b"\r\n\r\n")
        status = 200
        headers = []
        for line in head.decode().split("\r\n"):
            k, _, v = line.partition(": ")
            if k.lower() == "status": status = int(v.split()[0])
            elif k: headers.append((k, v))
        self.send_response(status)
        for k, v in headers: self.send_header(k, v)
        self.send_header("Content-Length", str(len(rest)))
        self.end_headers()
        self.wfile.write(rest)

    # git sends a push larger than http.postBuffer chunked
    def read_chunked(self):
        data = b""
        while True:
            size = int(self.rfile.readline().strip(), 16)
            if size == 0:
                self.rfile.readline()
                return data
            data += self.rfile.read(size)
            self.rfile.readline()

http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
