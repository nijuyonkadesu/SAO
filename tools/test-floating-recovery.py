#!/usr/bin/env python3
"""Emulator-only checks for sticky/system-bound recovery, dismissal and Force stop.

Install the debug APK and enable "Show icon on app launch" first. This starts and
kills SAO, temporarily allows its overlay, and leaves the app force-stopped. No
media picker, accounts, network search, alarms or production watchdog are used.
"""
import argparse
import json
import re
import shlex
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--apk", type=Path, help="Defaults to the standalone debug APK matching the emulator ABI")
    args = parser.parse_args()
    package = "one.njk.sao"

    def adb(*arguments, check=True):
        return subprocess.run(["adb", "-s", args.serial, *arguments], check=check,
                              capture_output=True, text=True, timeout=20).stdout.strip()

    def shell(*arguments, check=True):
        return adb("shell", shlex.join(arguments), check=check)

    if shell("getprop", "ro.kernel.qemu") != "1":
        raise SystemExit("Refusing to kill an app on a physical device; use an emulator.")
    if args.apk is None:
        abi = shell("getprop", "ro.product.cpu.abi")
        args.apk = Path("app/build/outputs/apk/debug") / f"app-{abi}-debug.apk"
    if not args.apk.is_file():
        raise SystemExit(f"Build the matching debug APK first: {args.apk}")
    shell("run-as", package, "id")  # Only the installed debug build is eligible.
    shell("su", "0", "id")  # Emulator fixture only: discard a non-exported service record.
    operation = shell("appops", "get", package, "SYSTEM_ALERT_WINDOW")
    match = re.search(r"SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default|foreground)", operation)
    previous_overlay = match.group(1) if match else "default"
    previous_services = shell("settings", "get", "secure", "enabled_accessibility_services")
    previous_enabled = shell("settings", "get", "secure", "accessibility_enabled")
    assistance = package + "/.floating.PasteAssistService"

    def set_assistance(enabled):
        services = [s for s in previous_services.split(":") if s not in ("", "null", assistance,
                    package + "/" + package + ".floating.PasteAssistService")]
        if enabled:
            services.append(assistance)
        shell("settings", "put", "secure", "enabled_accessibility_services", ":".join(services))
        shell("settings", "put", "secure", "accessibility_enabled", "1" if services else "0")

    def assistance_bound():
        status = shell("dumpsys", "activity", "services", package)
        # ConnectionRecord entries can outlive an unbound service; require a live record.
        return bool(re.search(r"^\s+\* ServiceRecord\{[^\n]+/\.floating\.PasteAssistService\b", status, re.M))

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

        # Erase the started-service record first: sticky recovery alone cannot handle this.
        launch()
        set_assistance(True)
        await_state("Android did not bind the enabled Paste assistance service", assistance_bound)
        time.sleep(1)  # Let the initial connected callback complete before discarding its service.
        shell("su", "0", "am", "stopservice", "-n", package + "/.floating.FloatingService", check=False)
        await_state("The fixture must discard the old foreground-service record", lambda: not foreground() and not visible(), 6)
        before = pid()
        started = time.monotonic()
        kill_process()
        await_state("System accessibility reconnect did not restore the requested icon",
                    lambda: pid() not in ("", before) and foreground() and visible())
        print(json.dumps({"discarded_service_recovered_on_system_reconnect": True,
                          "restart_seconds": round(time.monotonic() - started, 2)}), flush=True)

        # Exercise notification Stop's production action (the real touch-dismiss was tested above).
        shell("su", "0", "am", "startservice", "-n", package + "/.floating.FloatingService",
              "-a", package + ".floating.STOP", check=False)
        await_state("Stop did not stop the restored icon", lambda: not foreground() and not visible(), 6)
        before = pid()
        kill_process()
        await_state("The system must really reconnect assistance during this assertion",
                    lambda: pid() not in ("", before) and assistance_bound())
        stays_stopped(6)
        print(json.dumps({"manual_dismissal_survives_system_reconnect": True}), flush=True)

        # A real package-replacement event must work without accessibility or a live service.
        set_assistance(False)
        await_state("The update fixture must unbind accessibility", lambda: not assistance_bound())
        launch()
        shell("su", "0", "am", "stopservice", "-n", package + "/.floating.FloatingService", check=False)
        await_state("Discard the service before the app update", lambda: not foreground(), 6)
        adb("install", "-r", str(args.apk.resolve()))
        await_state("Package replacement did not restore the requested icon", lambda: foreground() and visible())
        print(json.dumps({"package_update_restores_requested_icon": True}), flush=True)
        shell("su", "0", "am", "startservice", "-n", package + "/.floating.FloatingService",
              "-a", package + ".floating.STOP", check=False)
        await_state("Stop must finish before installing again", lambda: not foreground(), 6)
        adb("install", "-r", str(args.apk.resolve()))
        stays_stopped(6)
        print(json.dumps({"manual_stop_survives_package_update": True}), flush=True)

        launch()
        shell("input", "keyevent", "KEYCODE_SLEEP")
        shell("cmd", "deviceidle", "force-idle")
        try:
            await_state("SAO must let the phone sleep with its icon session active",
                        lambda: "mWakefulness=Asleep" in shell("dumpsys", "power") and not visible(), 6)
            assert foreground(), "The foreground session should survive normal device sleep"
            locks = re.search(r"Wake Locks:.*?(?=Suspend Blockers:)", shell("dumpsys", "power"), re.S)
            assert locks and package not in locks.group(), "SAO must not hold a wake lock"
            print(json.dumps({"phone_sleeps_with_session_active": True, "sao_wake_locks": 0}), flush=True)
        finally:
            shell("cmd", "deviceidle", "unforce")
            shell("input", "keyevent", "KEYCODE_WAKEUP")
            shell("wm", "dismiss-keyguard")
        await_state("Unlock should restore the existing icon", lambda: foreground() and visible())

        launch()
        shell("am", "force-stop", package)
        stays_stopped(6)
        assert re.search(r"\bstopped=true\b", shell("dumpsys", "package", package))
        print(json.dumps({"force_stop_respected": True}), flush=True)
    finally:
        shell("cmd", "deviceidle", "unforce")
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")
        shell("am", "force-stop", package)
        shell("appops", "set", package, "SYSTEM_ALERT_WINDOW", previous_overlay)
        for setting, value in (("enabled_accessibility_services", previous_services),
                               ("accessibility_enabled", previous_enabled)):
            if value == "null":
                shell("settings", "delete", "secure", setting)
            else:
                shell("settings", "put", "secure", setting, value)


if __name__ == "__main__":
    main()
