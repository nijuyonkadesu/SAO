#!/usr/bin/env python3
"""Emulator-only checks for sticky recovery, manual dismissal and Force stop.

Install the debug APK and enable "Show icon on app launch" first. This starts and
kills SAO, temporarily allows its overlay, and leaves the app force-stopped. No
media picker, accounts, network search, alarms or production watchdog are used.
"""
import argparse
import json
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    package = "one.njk.sao"

    def adb(*arguments, check=True):
        return subprocess.run(["adb", "-s", args.serial, *arguments], check=check,
                              capture_output=True, text=True, timeout=20).stdout.strip()

    def shell(*arguments, check=True):
        return adb("shell", *arguments, check=check)

    if shell("getprop", "ro.kernel.qemu") != "1":
        raise SystemExit("Refusing to kill an app on a physical device; use an emulator.")
    shell("run-as", package, "id")  # Only the installed debug build is eligible.
    operation = shell("appops", "get", package, "SYSTEM_ALERT_WINDOW")
    match = re.search(r"SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default|foreground)", operation)
    previous_overlay = match.group(1) if match else "default"

    def pid():
        return shell("pidof", package, check=False)

    def foreground():
        status = shell("dumpsys", "activity", "services", package)
        return ".floating.FloatingService" in status and "isForeground=true" in status

    def window():
        windows = shell("dumpsys", "window", "windows")
        match = re.search(r"Window #\d+ Window\{[^\n]* SAO floating icon\}:(.*?)(?=\n  Window #|\Z)", windows, re.S)
        return match.group(1) if match else ""

    def visible():
        return "isVisible=true" in window()

    def await_state(label, condition, seconds=45):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if condition():
                return
            time.sleep(.25)
        raise AssertionError(label)

    def launch():
        shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c",
              "android.intent.category.LAUNCHER", "-n", package + "/.MainActivity")
        shell("input", "keyevent", "KEYCODE_HOME")
        await_state("Enable Show icon on app launch and verify overlay access", lambda: foreground() and visible())

    def kill_process():
        process = pid()
        if process:
            if not process.isdigit():
                raise AssertionError("Expected exactly one SAO main process")
            shell("run-as", package, "kill", "-9", process)
        return process

    def stays_stopped(seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if foreground() or visible():
                raise AssertionError("The explicitly stopped icon unexpectedly restarted")
            time.sleep(.4)

    try:
        shell("am", "force-stop", package)
        shell("appops", "set", package, "SYSTEM_ALERT_WINDOW", "allow")
        launch()
        before = pid()
        started = time.monotonic()
        kill_process()
        await_state("Android did not restore the sticky foreground service", lambda: pid() not in ("", before) and foreground() and visible())
        restart_seconds = round(time.monotonic() - started, 2)
        print(json.dumps({"system_kill_recovered": True, "restart_seconds": restart_seconds}), flush=True)

        # Exercise the real touch dismissal, including its existing long-press handler.
        frame = re.search(r"\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", window())
        if not frame:
            raise AssertionError("Cannot locate the overlay frame for an actual dismissal")
        dimensions = re.findall(r"(\d+)x(\d+)", shell("wm", "size"))
        width, height = map(int, dimensions[-1])
        left, top, right, bottom = map(int, frame.groups())
        x = (max(0, left) + min(width, right)) // 2
        y = (max(0, top) + min(height, bottom)) // 2
        shell("input", "swipe", str(x), str(y), str(x), str(y), "750")
        await_state("Long-press dismissal did not stop the icon", lambda: not foreground() and not visible(), 6)
        kill_process()
        stays_stopped(max(6, restart_seconds + 2))
        print(json.dumps({"manual_dismissal_survives_process_death": True}), flush=True)

        launch()
        shell("am", "force-stop", package)
        stays_stopped(6)
        assert re.search(r"\bstopped=true\b", shell("dumpsys", "package", package))
        print(json.dumps({"force_stop_respected": True}), flush=True)
    finally:
        shell("am", "force-stop", package)
        shell("appops", "set", package, "SYSTEM_ALERT_WINDOW", previous_overlay)


if __name__ == "__main__":
    main()
