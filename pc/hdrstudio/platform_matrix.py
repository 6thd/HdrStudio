"""Run existing export presets locally; never upload or alter platform checks."""
import argparse
import json
from pathlib import Path
import subprocess
import sys


def plan(source, folder):
    source = str(Path(source).resolve())
    folder = Path(folder).resolve()
    variants = [("pq", "tiktok_hdr", []), ("hlg", "tiktok_hlg", []),
                ("sdr", "tiktok_sdr", []),
                ("pq_plus", "tiktok_hdr", ["--set", "hdr10plus=true"])]
    executable = [sys.executable, "export"] if getattr(sys, "frozen", False) else [sys.executable, "-m", "hdrstudio", "export"]
    return [(name, [*executable, source,
                    "-o", str(folder / (name + ".mp4")), "--preset", preset,
                    "--quality", "off", "--resolution", "source",
                    "--fps", "source", *extra])
            for name, preset, extra in variants]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("folder")
    parser.add_argument("--run", action="store_true")
    args = parser.parse_args(argv)
    commands = plan(args.source, args.folder)
    if not args.run:
        print(json.dumps(commands, indent=2))
        return 0
    if not Path(args.source).is_file():
        parser.error("source must be a local file")
    folder = Path(args.folder).resolve()
    folder.mkdir(parents=True, exist_ok=False)
    results = {}
    for name, command in commands:
        with (folder / (name + ".log")).open("wb") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        results[name] = {"exit_code": result.returncode,
                         "platform_acceptance": "NOT_VERIFIED"}
        (folder / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    return int(any(item["exit_code"] for item in results.values()))


if __name__ == "__main__":
    raise SystemExit(main())
