#!/usr/bin/env bash
#
# 데스크톱 에뮬레이터(또는 연결된 아무 기기)에서 앱을 실제로 띄워 보는 스크립트.
#
# 하는 일:
#   1. 디버그 APK 를 찾거나 없으면 빌드한다
#   2. 화면 잠금을 풀고 APK 를 설치한다
#   3. MainActivity 가 요청하는 권한만 미리 허용한다(대화상자를 건너뛰기 위해)
#   4. 앱을 띄우고, 시작 위치를 넣고, 스크린샷을 남긴다
#   5. 죽었는지(FATAL EXCEPTION), 화면이 실제로 앞에 있는지 확인한다
#
# 사람이 눌러야 진행되는 부분은 자동으로 넘기지 않는다. 승차 확인처럼
# 오탐이 위험해서 일부러 사람 손에 맡긴 단계이기 때문이다.
#
# 사용:
#   bash tools/emulator_smoke.sh
#   bash tools/emulator_smoke.sh --route demo.sh          # 이동까지 재생
#   bash tools/emulator_smoke.sh --apk 내려받은.apk --no-build
#
set -euo pipefail

APK=""
SERIAL=""
OUT_DIR="build/desktop-smoke"
ROUTE=""
DO_BUILD=1
KEEP=0

# 디버그 빌드는 applicationIdSuffix 로 .debug 가 붙는다. 릴리스와 나란히 깔린다.
PACKAGE="kr.eodiga.wayfinder.debug"
ACTIVITY="kr.eodiga.wayfinder.MainActivity"

# 앱이 시작할 때 실제로 묻는 권한만 준다(MainActivity.requestCorePermissions).
# 문자·전화·음악 권한은 일부러 두지 않는다 — 없을 때 대체 경로로 도는지가
# 확인 대상이고, 에뮬레이터에서 문자가 실제로 나가지도 않는다.
CORE_PERMISSIONS=(
  android.permission.ACCESS_FINE_LOCATION
  android.permission.ACCESS_COARSE_LOCATION
  android.permission.POST_NOTIFICATIONS
)

# 기본 시작 위치. make_demo_gpx.py 의 기본 시나리오 출발점과 같다.
START_LAT="37.61000"
START_LON="126.92900"

die() { printf '\n✗ %s\n' "$*" >&2; exit 1; }
step() { printf '\n── %s\n' "$*"; }
note() { printf '   %s\n' "$*"; }

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//; /^set -euo/d'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:?--apk 에 파일 경로가 필요합니다}"; shift 2 ;;
    --serial) SERIAL="${2:?--serial 에 기기 이름이 필요합니다}"; shift 2 ;;
    --out) OUT_DIR="${2:?--out 에 디렉터리가 필요합니다}"; shift 2 ;;
    --route) ROUTE="${2:?--route 에 파일 경로가 필요합니다}"; shift 2 ;;
    --no-build) DO_BUILD=0; shift ;;
    --keep) KEEP=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "모르는 옵션: $1" ;;
  esac
done

command -v adb >/dev/null 2>&1 || die "adb 가 PATH 에 없습니다. Android SDK 의 platform-tools 를 PATH 에 넣어 주세요."

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

step "기기를 기다립니다"
"${ADB[@]}" wait-for-device
# 부팅이 덜 끝난 에뮬레이터에 설치하면 INSTALL_FAILED_* 로 떨어진다.
for _ in $(seq 1 60); do
  boot="$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n[:space:]')"
  [ "$boot" = "1" ] && break
  sleep 2
done
[ "${boot:-}" = "1" ] || die "기기가 부팅을 끝내지 못했습니다."
note "$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r') / Android $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"

# 이 앱은 Google Play services 의 위치 제공자를 쓴다(FusedLocationProviderClient).
# AOSP 시스템 이미지에는 그것이 없어서, 앱은 뜨지만 위치가 영원히 안 잡힌다.
# 데스크톱 시험이 "안 된다" 고 보이는 가장 흔한 원인이라 미리 짚는다.
if ! "${ADB[@]}" shell pm list packages 2>/dev/null | grep -q "com.google.android.gms"; then
  note "⚠ Google Play services 가 없는 이미지입니다(AOSP)."
  note "  앱은 뜨지만 위치가 잡히지 않습니다. AVD 를 'Google APIs' 이미지로 다시 만드세요."
fi

step "APK 를 준비합니다"
if [ -z "$APK" ]; then
  APK="$(ls -t app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1 || true)"
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  [ "$DO_BUILD" -eq 1 ] || die "APK 를 찾지 못했습니다. --apk 로 지정하거나 --no-build 를 빼 주세요."
  note "APK 가 없어 빌드합니다: ./gradlew assembleDebug"
  ./gradlew --console=plain assembleDebug
  APK="$(ls -t app/build/outputs/apk/debug/*.apk | head -1)"
fi
note "$APK"

step "화면 잠금을 풉니다"
"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true

step "설치합니다"
"${ADB[@]}" install -r -d "$APK"

step "시작 권한을 허용합니다"
for perm in "${CORE_PERMISSIONS[@]}"; do
  # POST_NOTIFICATIONS 는 Android 13 미만에 없다. 없는 권한은 조용히 넘긴다.
  if "${ADB[@]}" shell pm grant "$PACKAGE" "$perm" >/dev/null 2>&1; then
    note "허용: ${perm##*.}"
  else
    note "건너뜀(이 안드로이드 버전에 없는 권한): ${perm##*.}"
  fi
done

mkdir -p "$OUT_DIR"

step "앱을 띄웁니다"
"${ADB[@]}" logcat -c >/dev/null 2>&1 || true
"${ADB[@]}" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
"${ADB[@]}" shell am start -W -n "$PACKAGE/$ACTIVITY" >/dev/null

# 시작 위치를 넣는다. 이 앱은 홈 화면에서 현재 위치를 한 번 읽어
# 주변 정류소를 찾으므로, 좌표가 없으면 홈에서 더 나아가지 않는다.
# `geo fix` 는 경도를 먼저 받는다.
if "${ADB[@]}" emu geo fix "$START_LON" "$START_LAT" >/dev/null 2>&1; then
  note "시작 위치: $START_LAT, $START_LON"
else
  note "⚠ geo fix 실패 — 실제 기기이거나 에뮬레이터 콘솔에 붙지 못했습니다."
fi

sleep 5
"${ADB[@]}" exec-out screencap -p > "$OUT_DIR/01-home.png"
note "스크린샷: $OUT_DIR/01-home.png"

step "살아 있는지 확인합니다"
"${ADB[@]}" logcat -d > "$OUT_DIR/logcat.txt" 2>/dev/null || true

if grep -qE "FATAL EXCEPTION|ANR in $PACKAGE" "$OUT_DIR/logcat.txt"; then
  grep -A 30 -E "FATAL EXCEPTION|ANR in $PACKAGE" "$OUT_DIR/logcat.txt" | head -40 >&2
  die "앱이 죽었습니다. 전체 로그: $OUT_DIR/logcat.txt"
fi

focus="$("${ADB[@]}" shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' || true)"
activities="$("${ADB[@]}" shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity' || true)"
if ! printf '%s%s' "$focus" "$activities" | grep -q "$PACKAGE"; then
  note "화면 앞에 있는 것: ${focus:-(확인 못 함)}"
  die "앱이 화면 앞에 없습니다. 스크린샷과 $OUT_DIR/logcat.txt 를 보세요."
fi
note "앱이 화면 앞에 떠 있습니다."

# 인증키가 없으면 앱은 "인증키 없음" 을 화면으로 알린다(ServiceKeyProvider).
# 실패가 아니라 예상된 상태이므로 안내만 한다.
if "${ADB[@]}" exec-out uiautomator dump /dev/tty 2>/dev/null | grep -q "인증키"; then
  note "⚠ 인증키 없이 실행 중입니다. 실시간 데이터를 보려면 local.properties 에 키를 넣고 다시 빌드하세요."
fi

if [ -n "$ROUTE" ]; then
  [ -f "$ROUTE" ] || die "경로 파일이 없습니다: $ROUTE"
  step "이동 경로를 재생합니다: $ROUTE"
  note "재생이 끝날 때까지 기다립니다. 도중에 화면을 보시려면 다른 창에서 에뮬레이터를 보세요."
  bash "$ROUTE"
  "${ADB[@]}" exec-out screencap -p > "$OUT_DIR/02-after-route.png"
  note "스크린샷: $OUT_DIR/02-after-route.png"
  "${ADB[@]}" logcat -d > "$OUT_DIR/logcat.txt" 2>/dev/null || true
  if grep -qE "FATAL EXCEPTION" "$OUT_DIR/logcat.txt"; then
    die "이동 재생 중에 앱이 죽었습니다. 로그: $OUT_DIR/logcat.txt"
  fi
fi

if [ "$KEEP" -eq 0 ]; then
  "${ADB[@]}" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
fi

printf '\n✓ 앱이 데스크톱 에뮬레이터에서 떴고, 죽지 않았습니다. 결과: %s\n' "$OUT_DIR"
