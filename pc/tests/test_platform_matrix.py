from pathlib import Path
import json

import pytest

from hdrstudio import platform_matrix


def test_matrix_plan(tmp_path):
    source = tmp_path / "source with spaces.mp4"
    folder = tmp_path / "exports"
    commands = platform_matrix.plan(source, folder)
    assert [name for name, _ in commands] == ["pq", "hlg", "sdr", "pq_plus"]
    for name, cmd in commands:
        assert cmd[4] == str(source.resolve())
        assert cmd[cmd.index("-o") + 1] == str(folder.resolve() / (name + ".mp4"))
        assert cmd[cmd.index("--quality") + 1] == "off"
        assert cmd[cmd.index("--fps") + 1] == "source"
    assert commands[-1][1][-2:] == ["--set", "hdr10plus=true"]
    assert not folder.exists()


def test_matrix_dry_run(tmp_path, capsys):
    folder = tmp_path / "exports"
    assert platform_matrix.main([str(tmp_path / "source.mp4"), str(folder)]) == 0
    assert len(json.loads(capsys.readouterr().out)) == 4
    assert not folder.exists()


def test_matrix_refuses_existing_folder(tmp_path):
    source = tmp_path / "source.mp4"
    source.touch()
    with pytest.raises(FileExistsError):
        platform_matrix.main([str(source), str(tmp_path), "--run"])
