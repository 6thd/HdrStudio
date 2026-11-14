import sys

import pytest

from hdrstudio import diag
from hdrstudio.__main__ import main


def test_peak_memory_is_measured():
    assert diag.peak_memory_mb() > 10


def test_environment_report():
    env = diag.environment()
    for key in ("os", "cpu", "cores", "memoryMB", "adapters", "aiRuntimes", "ffmpeg", "aiBenchmarks", "packaged"):
        assert key in env
    assert env["cores"] >= 1 and "ONNX Runtime CPU" in env["aiRuntimes"] and env["packaged"] is False
    if sys.platform == "win32":
        assert env["memoryMB"] > 256


def test_diag_command_is_registered(capsys):
    with pytest.raises(SystemExit):
        main(["diag", "--help"])
    assert "--size" in capsys.readouterr().out
