"""The local API must not be callable from a web page open in the browser."""
import http.client
import json
import threading
from http.server import ThreadingHTTPServer

import pytest

from hdrstudio.server import CSP, MAX_BODY, make_handler

TOKEN = "test-token"


class StubApp:
    job = None

    def __init__(self):
        self.calls = []

    def log(self, msg):
        pass

    def status(self, q):
        self.calls.append(("status", q))
        return {"ok": True}

    def export(self, q):
        self.calls.append(("export", q))
        return {"ok": True}


@pytest.fixture()
def srv():
    app = StubApp()
    s = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(app, TOKEN))
    threading.Thread(target=s.serve_forever, daemon=True).start()
    yield app, s.server_address[1]
    s.shutdown()
    s.server_close()


def call(port, method, path, body=None, headers=None, host=None):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    c.putrequest(method, path, skip_host=True)
    c.putheader("Host", host or f"127.0.0.1:{port}")
    data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else b""
    for k, v in (headers or {}).items():
        c.putheader(k, v)
    c.putheader("Content-Length", str(len(data)))
    c.endheaders()
    c.send(data)
    r = c.getresponse()
    out = r.status, r.read(), dict(r.getheaders())
    c.close()
    return out


GOOD = {"Content-Type": "application/json", "X-HDR-Token": TOKEN}


def test_page_carries_token_and_csp(srv):
    _, port = srv
    status, body, headers = call(port, "GET", "/")
    assert status == 200
    assert f'content="{TOKEN}"'.encode() in body and b"__HDR_TOKEN__" not in body
    assert headers["Content-Security-Policy"] == CSP
    assert headers["X-Content-Type-Options"] == "nosniff"


def test_api_with_token(srv):
    app, port = srv
    status, body, _ = call(port, "POST", "/api/status", {"since": 3}, GOOD)
    assert status == 200 and json.loads(body) == {"ok": True}
    assert app.calls == [("status", {"since": 3})]


@pytest.mark.parametrize("headers", [
    {"Content-Type": "application/json"},                                        # no token
    {"Content-Type": "application/json", "X-HDR-Token": "wrong"},
    {**GOOD, "Origin": "https://evil.example"},                                   # cross-site fetch
    {**GOOD, "Origin": "null"},
    {**GOOD, "Sec-Fetch-Site": "cross-site"},
])
def test_api_rejected(srv, headers):
    app, port = srv
    status, _, _ = call(port, "POST", "/api/export", {"settings": {}}, headers)
    assert status == 403 and app.calls == []


def test_own_origin_allowed(srv):
    app, port = srv
    headers = {**GOOD, "Origin": f"http://127.0.0.1:{port}", "Sec-Fetch-Site": "same-origin"}
    assert call(port, "POST", "/api/status", {}, headers)[0] == 200


def test_dns_rebinding_host_rejected(srv):
    app, port = srv
    assert call(port, "POST", "/api/export", {}, GOOD, host=f"evil.example:{port}")[0] == 403
    assert call(port, "GET", "/", host="evil.example")[0] == 403
    assert app.calls == []


def test_form_content_type_rejected(srv):
    app, port = srv
    status, _, _ = call(port, "POST", "/api/export", b"{}", {"Content-Type": "text/plain", "X-HDR-Token": TOKEN})
    assert status == 415 and app.calls == []


def test_body_limits(srv):
    app, port = srv
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    c.putrequest("POST", "/api/export")
    for k, v in GOOD.items():
        c.putheader(k, v)
    c.putheader("Content-Length", str(MAX_BODY + 1))
    c.endheaders()
    assert c.getresponse().status == 413
    c.close()
    assert call(port, "POST", "/api/export", b"[1,2]", GOOD)[0] == 400
    assert call(port, "POST", "/api/export", b"{not json", GOOD)[0] == 400
    assert app.calls == []


def test_get_cannot_reach_api(srv):
    app, port = srv
    assert call(port, "GET", f"/api/export?k={TOKEN}")[0] == 403
    assert call(port, "GET", "/api/thumb?path=x")[0] == 403          # image without token
    assert call(port, "GET", f"/api/live?k={TOKEN}")[0] == 404       # authorised, no job running
    assert app.calls == []


def test_unknown_route_and_traversal(srv):
    _, port = srv
    assert call(port, "POST", "/api/__init__", {}, GOOD)[0] == 404
    assert call(port, "POST", "/api/log", {}, GOOD)[0] == 404         # a real attribute that is not a route
    assert call(port, "GET", "/../server.py")[0] == 404
    assert call(port, "GET", "/..%2fserver.py")[0] == 404
