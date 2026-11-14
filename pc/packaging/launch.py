"""Entry point of the packaged executables (PyInstaller)."""
import sys
import traceback


def _fatal(text: str):
    """The app window has no console: show start-up failures instead of exiting silently."""
    try:
        import ctypes
        ctypes.windll.user32.MessageBoxW(None, text[-1800:], "HDR Studio", 0x10)
    except Exception:
        pass


if __name__ == "__main__":
    try:
        from hdrstudio.__main__ import main
        code = main()
    except SystemExit:
        raise
    except BaseException:
        if sys.stderr is None or not sys.stderr.isatty():
            _fatal(traceback.format_exc())
        raise
    sys.exit(code)
