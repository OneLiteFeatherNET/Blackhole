"""HTTP layer. `handle` is a pure function of (model, method, path, body), so the routes are tested
without a socket. `make_server` only wires it to the standard library HTTP server.
"""

from __future__ import annotations

import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .scorer import ToxicityModel

logger = logging.getLogger("toxicity")

MAX_BODY_BYTES = 64 * 1024

Response = tuple[int, dict]


def handle(model: ToxicityModel, method: str, path: str, body: bytes) -> Response:
    if path == "/health":
        return (200, {"status": "ok"}) if method == "GET" else (405, {"error": "method not allowed"})
    if path == "/v1/score":
        if method != "POST":
            return 405, {"error": "method not allowed"}
        return _score(model, body)
    return 404, {"error": "not found"}


def _score(model: ToxicityModel, body: bytes) -> Response:
    if len(body) > MAX_BODY_BYTES:
        return 413, {"error": "body too large"}
    try:
        payload = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return 400, {"error": "body must be JSON"}
    texts = payload.get("texts") if isinstance(payload, dict) else None
    if not isinstance(texts, list) or not texts:
        return 400, {"error": "texts must be a non-empty list"}
    if not all(isinstance(text, str) for text in texts):
        return 400, {"error": "every text must be a string"}
    scores = model.score(texts)
    # Batch size and model version only. Message text is never logged.
    logger.info("scored batch size=%d model_version=%s", len(texts), model.version)
    return 200, {"model_version": model.version, "results": [{"overall": s} for s in scores]}


def make_server(model: ToxicityModel, host: str, port: int) -> ThreadingHTTPServer:
    class Handler(BaseHTTPRequestHandler):
        def _respond(self, method: str) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY_BYTES:
                status, reply = 413, {"error": "body too large"}
            else:
                body = self.rfile.read(length) if length else b""
                status, reply = handle(model, method, self.path, body)
            encoded = json.dumps(reply).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def do_GET(self) -> None:  # noqa: N802 (stdlib naming)
            self._respond("GET")

        def do_POST(self) -> None:  # noqa: N802
            self._respond("POST")

        def log_message(self, format: str, *args: object) -> None:  # noqa: A002
            # The stdlib access log prints request lines; keep it off so that no payload reaches the logs.
            return

    return ThreadingHTTPServer((host, port), Handler)
