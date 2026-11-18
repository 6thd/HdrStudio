"""Platform Lab desktop API and UI smoke contracts."""
from http.server import ThreadingHTTPServer
from pathlib import Path
import json
import threading
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import pytest

from hdrstudio.server import make_handler, UI, ROUTES


class FakeApp:
    def platform_lab(self, query):
        return {"source": query["path"], "status": "OBSERVED"}


def test_lab_exposed_as_authenticated_route():
    assert "platform_lab" in ROUTES
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(FakeApp(), "test-token"))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = "http://127.0.0.1:%d/api/platform_lab" % server.server_port
    payload = json.dumps({"path": "fixture.mp4"}).encode()
    try:
        request = Request(url, data=payload, headers={
            "Content-Type": "application/json",
            "X-HDR-Token": "test-token"}, method="POST")
        with urlopen(request, timeout=10) as response:
            assert json.loads(response.read()) == {
                "source": "fixture.mp4", "status": "OBSERVED"}
        with pytest.raises(HTTPError) as err:
            urlopen(Request(url, data=payload,
                            headers={"Content-Type": "application/json"},
                            method="POST"), timeout=10)
        assert err.value.code == 403
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_lab_controls_exist_and_safe_text_render():
    html = (UI / "index.html").read_text(encoding="utf-8")
    script = (UI / "app.js").read_text(encoding="utf-8")
    assert 'id="platformLabSection"' in html
    assert 'id="labAnalyze"' in html
    assert 'id="labResult"' in html
    assert 'api("platform_lab"' in script
    assert 'out.textContent = JSON.stringify(result, null, 2)' in script


def test_lab_compare_path_quotes_are_stripped():
    html = (UI / "index.html").read_text(encoding="utf-8")
    script = (UI / "app.js").read_text(encoding="utf-8")
    assert 'replace(/^"(.*)"$/, "$1")' in script
    assert 'placeholder="C:\\Videos\\published.mp4"' in html
