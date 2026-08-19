#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
데스크톱(윈도우·맥·리눅스)의 에뮬레이터에서 앱을 실제로 띄워 보는 도구.

하는 일:
  1. adb 를 찾는다 (PATH 에 없으면 SDK 기본 위치들을 뒤진다)
  2. 디버그 APK 를 찾거나 없으면 빌드한다
  3. 화면 잠금을 풀고 설치한다
  4. MainActivity 가 실제로 묻는 권한만 미리 허용한다(대화상자를 건너뛰기 위해)
  5. 앱을 띄우고, 시작 위치를 넣고, 스크린샷을 남긴다
  6. 죽었는지(FATAL EXCEPTION), 화면이 실제로 앞에 있는지 확인한다
  7. --simulate 면 가짜 이동까지 재생한다

셸 문법을 쓰지 않으므로 윈도우 명령 프롬프트·PowerShell 에서도 그대로 돕니다.
Git Bash 나 WSL 이 필요하지 않습니다.

사용:
  python3 tools/desktop_run.py                          # 설치 → 실행 → 확인
  python3 tools/desktop_run.py --simulate --speed 10    # 가짜 이동까지 재생
  python3 tools/desktop_run.py --apk 내려받은.apk --no-build
  py tools\\desktop_run.py                               # 윈도우

사람이 눌러야 진행되는 단계는 자동으로 넘기지 않습니다. 승차 확인처럼
오탐이 위험해서 일부러 사람 손에 맡긴 단계이기 때문입니다.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_demo_gpx as route  # noqa: E402  경로 계산을 한 곳에서만 한다

# 디버그 빌드는 applicationIdSuffix 로 .debug 가 붙는다. 릴리스와 나란히 깔린다.
PACKAGE = "kr.eodiga.wayfinder.debug"
ACTIVITY = "kr.eodiga.wayfinder.MainActivity"

# 앱이 시작할 때 실제로 묻는 권한만 준다(MainActivity.requestCorePermissions).
# 문자·전화·음악 권한은 일부러 두지 않는다 — 없을 때 대체 경로로 도는지가
# 확인 대상이고, 에뮬레이터에서 문자가 실제로 나가지도 않는다.
CORE_PERMISSIONS = (
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.POST_NOTIFICATIONS",
)

IS_WINDOWS = os.name == "nt"


class Failed(Exception):
    """확인에 실패했다. 종료 코드 1 로 나간다."""


def step(text: str) -> None:
    print(f"\n── {text}", flush=True)


def note(text: str) -> None:
    print(f"   {text}", flush=True)


def gradle_wrapper_name() -> str:
    """윈도우는 gradlew.bat, 나머지는 gradlew."""
    return "gradlew.bat" if IS_WINDOWS else "gradlew"


def adb_candidates() -> list[Path]:
    """adb 가 있을 만한 자리. 위에 있는 것을 먼저 쓴다.

    윈도우에서 "adb: command not found" 로 막히는 일이 가장 많다.
    Android Studio 는 PATH 를 건드리지 않으므로 기본 설치 위치를 직접 뒤진다.
    """
    exe = "adb.exe" if IS_WINDOWS else "adb"
    roots: list[Path] = []
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(var)
        if value:
            roots.append(Path(value))
    home = Path.home()
    if IS_WINDOWS:
        local = os.environ.get("LOCALAPPDATA")
        if local:
            roots.append(Path(local) / "Android" / "Sdk")
    roots += [
        home / "Library" / "Android" / "sdk",  # 맥 (Android Studio 기본)
        home / "Android" / "Sdk",              # 리눅스 (Android Studio 기본)
        home / "AppData" / "Local" / "Android" / "Sdk",
    ]
    return [root / "platform-tools" / exe for root in roots]


def find_adb() -> list[str]:
    from shutil import which

    found = which("adb")
    if found:
        return [found]
    for candidate in adb_candidates():
        if candidate.is_file():
            return [str(candidate)]
    raise Failed(
        "adb 를 찾지 못했습니다. Android SDK 의 platform-tools 를 PATH 에 넣거나,\n"
        "  ANDROID_HOME 환경변수를 SDK 경로로 지정해 주세요."
    )


class Device:
    """adb 한 대와 이야기하는 통로. 셸을 거치지 않고 인수 배열로만 부른다."""

    def __init__(self, adb: list[str], serial: str | None) -> None:
        self.base = list(adb)
        if serial:
            self.base += ["-s", serial]

    def run(self, *args: str, check: bool = False, timeout: float = 120) -> tuple[int, str]:
        try:
            done = subprocess.run(
                self.base + list(args),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired:
            if check:
                raise Failed(f"adb 응답이 없습니다: {' '.join(args)}") from None
            return 124, ""
        text = done.stdout.decode("utf-8", errors="replace").strip()
        if check and done.returncode != 0:
            raise Failed(f"adb {' '.join(args)} 실패:\n{text}")
        return done.returncode, text

    def binary(self, *args: str, timeout: float = 120) -> bytes:
        """스크린샷처럼 바이너리를 그대로 받아야 하는 경우.

        윈도우에서 `adb shell screencap` 을 파일로 흘리면 줄바꿈이 바뀌어
        PNG 가 깨진다. exec-out 으로 받고 바이트로 다루면 그 문제가 없다.
        """
        done = subprocess.run(
            self.base + list(args), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout
        )
        return done.stdout

    def prop(self, name: str) -> str:
        return self.run("shell", "getprop", name)[1].strip()


def wait_for_device(dev: Device) -> None:
    step("기기를 기다립니다")
    dev.run("wait-for-device", check=True, timeout=300)
    # 부팅이 덜 끝난 에뮬레이터에 설치하면 INSTALL_FAILED_* 로 떨어진다.
    for _ in range(60):
        if dev.prop("sys.boot_completed") == "1":
            break
        time.sleep(2)
    else:
        raise Failed("기기가 부팅을 끝내지 못했습니다.")
    note(f"{dev.prop('ro.product.model')} / Android {dev.prop('ro.build.version.release')}")

    # 이 앱은 Google Play services 의 위치 제공자를 쓴다(FusedLocationProviderClient).
    # AOSP 시스템 이미지에는 그것이 없어서, 앱은 뜨지만 위치가 영원히 안 잡힌다.
    # 데스크톱 시험이 "안 된다" 고 보이는 가장 흔한 원인이라 미리 짚는다.
    if "com.google.android.gms" not in dev.run("shell", "pm", "list", "packages")[1]:
        note("⚠ Google Play services 가 없는 이미지입니다(AOSP).")
        note("  앱은 뜨지만 위치가 잡히지 않습니다. AVD 를 'Google APIs' 이미지로 다시 만드세요.")


def resolve_apk(repo: Path, given: str | None, do_build: bool) -> Path:
    step("APK 를 준비합니다")
    if given:
        apk = Path(given)
        if not apk.is_file():
            raise Failed(f"APK 가 없습니다: {apk}")
        note(str(apk))
        return apk

    out_dir = repo / "app" / "build" / "outputs" / "apk" / "debug"
    built = sorted(out_dir.glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
    if built:
        note(str(built[0].relative_to(repo)))
        return built[0]

    if not do_build:
        raise Failed("APK 를 찾지 못했습니다. --apk 로 지정하거나 --no-build 를 빼 주세요.")

    wrapper = repo / gradle_wrapper_name()
    note(f"APK 가 없어 빌드합니다: {wrapper.name} assembleDebug")
    done = subprocess.run([str(wrapper), "--console=plain", "assembleDebug"], cwd=repo)
    if done.returncode != 0:
        raise Failed("빌드가 실패했습니다.")
    built = sorted(out_dir.glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not built:
        raise Failed("빌드는 됐지만 APK 를 찾지 못했습니다.")
    note(str(built[0].relative_to(repo)))
    return built[0]


def install(dev: Device, apk: Path) -> None:
    step("화면 잠금을 풉니다")
    dev.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    dev.run("shell", "wm", "dismiss-keyguard")

    step("설치합니다")
    code, text = dev.run("install", "-r", "-d", str(apk), timeout=600)
    if code != 0 or "Success" not in text:
        raise Failed(f"설치가 실패했습니다:\n{text}")
    note("Success")

    step("시작 권한을 허용합니다")
    for perm in CORE_PERMISSIONS:
        code, _ = dev.run("shell", "pm", "grant", PACKAGE, perm)
        if code == 0:
            note(f"허용: {perm.rsplit('.', 1)[-1]}")
        else:
            # POST_NOTIFICATIONS 는 Android 13 미만에 없다. 없는 권한은 조용히 넘긴다.
            note(f"건너뜀(이 안드로이드 버전에 없는 권한): {perm.rsplit('.', 1)[-1]}")


def geo_fix(dev: Device, lat: float, lon: float) -> bool:
    """에뮬레이터에 좌표를 넣는다. `geo fix` 는 경도를 먼저 받는다."""
    code, _ = dev.run("emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}", timeout=30)
    return code == 0


def launch(dev: Device, out_dir: Path, start: route.Leg) -> None:
    step("앱을 띄웁니다")
    dev.run("logcat", "-c")
    dev.run("shell", "am", "force-stop", PACKAGE)
    code, text = dev.run("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}", timeout=120)
    if code != 0 or "Error" in text:
        raise Failed(f"앱을 띄우지 못했습니다:\n{text}")

    # 시작 위치를 넣는다. 이 앱은 홈 화면에서 현재 위치를 한 번 읽어
    # 주변 정류소를 찾으므로, 좌표가 없으면 홈에서 더 나아가지 않는다.
    if geo_fix(dev, start.lat, start.lon):
        note(f"시작 위치: {start.lat}, {start.lon}")
    else:
        note("⚠ geo fix 실패 — 실제 기기이거나 에뮬레이터 콘솔에 붙지 못했습니다.")

    time.sleep(5)
    screenshot(dev, out_dir / "01-home.png")


def screenshot(dev: Device, path: Path) -> None:
    data = dev.binary("exec-out", "screencap", "-p")
    if not data.startswith(b"\x89PNG"):
        note(f"⚠ 스크린샷을 받지 못했습니다: {path.name}")
        return
    path.write_bytes(data)
    note(f"스크린샷: {path}")


def check_alive(dev: Device, out_dir: Path) -> None:
    step("살아 있는지 확인합니다")
    log_path = out_dir / "logcat.txt"
    log = dev.run("logcat", "-d", timeout=120)[1]
    log_path.write_text(log, encoding="utf-8")

    for marker in ("FATAL EXCEPTION", f"ANR in {PACKAGE}"):
        if marker in log:
            start = log.index(marker)
            print(log[start : start + 2000], file=sys.stderr)
            raise Failed(f"앱이 죽었습니다. 전체 로그: {log_path}")

    focus = dev.run("shell", "dumpsys", "window")[1]
    activities = dev.run("shell", "dumpsys", "activity", "activities")[1]
    focus_line = next((l for l in focus.splitlines() if "mCurrentFocus" in l), "")
    resumed = next(
        (l for l in activities.splitlines() if "mResumedActivity" in l or "topResumedActivity" in l),
        "",
    )
    if PACKAGE not in focus_line and PACKAGE not in resumed:
        note(f"화면 앞에 있는 것: {focus_line.strip() or '(확인 못 함)'}")
        raise Failed(f"앱이 화면 앞에 없습니다. 스크린샷과 {log_path} 를 보세요.")
    note("앱이 화면 앞에 떠 있습니다.")

    # 인증키가 없으면 앱은 "인증키 없음" 을 화면으로 알린다(ServiceKeyProvider).
    # 실패가 아니라 예상된 상태이므로 안내만 한다.
    ui = dev.binary("exec-out", "uiautomator", "dump", "/dev/tty", timeout=60)
    if "인증키".encode() in ui:
        note("⚠ 인증키 없이 실행 중입니다. 실시간 데이터를 보려면 local.properties 에 키를 넣고 다시 빌드하세요.")


def simulate(dev: Device, out_dir: Path, legs: tuple[route.Leg, ...], speed: float, args) -> None:
    """가짜로 걷게 만든다. 이 앱은 좌표가 움직여야 화면이 진행된다."""
    points = list(
        route.sample_points(
            legs,
            walk_mps=args.walk_speed,
            bus_mps=args.bus_speed,
            wait_seconds=args.wait_seconds,
            interval=args.interval,
        )
    )
    total = points[-1][0] / speed if points else 0.0
    step(f"가짜 이동을 재생합니다 — 좌표 {len(points)}개, {total / 60:.1f}분")
    note("에뮬레이터 창을 함께 보시면 화면이 스스로 넘어가는 것이 보입니다.")

    previous = 0.0
    for index, (elapsed, lat, lon) in enumerate(points):
        gap = (elapsed - previous) / speed
        if gap > 0:
            time.sleep(gap)
        previous = elapsed
        geo_fix(dev, lat, lon)
        if index and index % 20 == 0:
            note(f"{index}/{len(points)} 지점 …")

    screenshot(dev, out_dir / "02-after-route.png")
    log = dev.run("logcat", "-d", timeout=120)[1]
    (out_dir / "logcat.txt").write_text(log, encoding="utf-8")
    if "FATAL EXCEPTION" in log:
        raise Failed(f"이동 재생 중에 앱이 죽었습니다. 로그: {out_dir / 'logcat.txt'}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="데스크톱 에뮬레이터에서 앱을 띄워 확인한다.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--apk", help="설치할 APK. 없으면 찾고, 그래도 없으면 빌드한다.")
    parser.add_argument("--serial", help="기기 이름(예: emulator-5554). 여러 대일 때 필요.")
    parser.add_argument("--out", default="build/desktop-smoke", help="스크린샷·로그를 남길 곳.")
    parser.add_argument("--no-build", action="store_true", help="APK 가 없어도 빌드하지 않는다.")
    parser.add_argument("--keep", action="store_true", help="끝나고 앱을 종료하지 않는다.")
    parser.add_argument("--simulate", action="store_true", help="가짜 이동을 재생한다.")
    parser.add_argument("--speed", type=float, default=1.0, help="재생 배속.")
    parser.add_argument(
        "--leg",
        action="append",
        type=route.parse_leg,
        metavar="종류:위도,경도",
        help="이동 구간(walk|wait|bus). 없으면 기본 시나리오.",
    )
    parser.add_argument("--walk-speed", type=float, default=route.DEFAULT_WALK_MPS)
    parser.add_argument("--bus-speed", type=float, default=route.DEFAULT_BUS_MPS)
    parser.add_argument("--wait-seconds", type=float, default=90.0)
    parser.add_argument("--interval", type=float, default=3.0)
    return parser


def main(argv: list[str]) -> int:
    # 윈도우 콘솔은 기본 인코딩이 UTF-8 이 아니어서 한글 출력에서 죽을 수 있다.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    args = build_parser().parse_args(argv)
    if args.speed <= 0:
        print("--speed 는 0 보다 커야 합니다.", file=sys.stderr)
        return 2

    repo = Path(__file__).resolve().parent.parent
    legs = tuple(args.leg) if args.leg else route.DEFAULT_LEGS
    if len(legs) < 2:
        print("구간은 최소 두 개가 필요합니다.", file=sys.stderr)
        return 2

    try:
        dev = Device(find_adb(), args.serial)
        wait_for_device(dev)
        apk = resolve_apk(repo, args.apk, do_build=not args.no_build)
        install(dev, apk)

        out_dir = Path(args.out)
        if not out_dir.is_absolute():
            out_dir = repo / out_dir
        out_dir.mkdir(parents=True, exist_ok=True)

        launch(dev, out_dir, legs[0])
        check_alive(dev, out_dir)
        if args.simulate:
            simulate(dev, out_dir, legs, args.speed, args)
        if not args.keep:
            dev.run("shell", "am", "force-stop", PACKAGE)
    except Failed as e:
        print(f"\n✗ {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n중단했습니다.", file=sys.stderr)
        return 130

    print(f"\n✓ 앱이 데스크톱 에뮬레이터에서 떴고, 죽지 않았습니다. 결과: {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
