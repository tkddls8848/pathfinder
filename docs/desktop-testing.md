# 데스크톱에서 앱 구동 시험하기

**됩니다.** 폰 없이 PC(윈도우·맥·리눅스)에서 이 앱을 그대로 실행할 수 있습니다.

안드로이드 에뮬레이터는 그 자체가 데스크톱 프로그램입니다. 안드로이드 한 대를
PC 안에 통째로 띄우는 것이라, 이 앱이 쓰는 GPS·음성 안내·진동·알림·포그라운드
서비스가 모두 그 안에서 돕니다. "안드로이드 전용"이라는 말은 *아이폰에서 안 된다*는
뜻이지, *PC 에서 못 본다*는 뜻이 아닙니다.

다만 한 가지 함정이 있어서 "데스크톱에서는 안 되더라"는 오해가 생깁니다.

> **이 앱은 버튼으로 넘어가는 앱이 아닙니다.**
> 위치가 실제로 움직여야 도보 → 정류장 도착 → 대기 로 넘어갑니다
> ([`JourneyController.trackLocation`](../app/src/main/java/kr/eodiga/wayfinder/service/JourneyController.kt)).
> 에뮬레이터를 켜기만 하면 GPS 좌표가 고정되어 있어 홈 화면에서 멈춘 것처럼 보입니다.
> 고장이 아니라, 가짜 이동을 넣어 주어야 하는 것입니다. 그 도구가 아래
> [`tools/make_demo_gpx.py`](../tools/make_demo_gpx.py) 입니다.

---

## 어떤 방법이 있나

| | A. 에뮬레이터 | B. 기기 없이 JVM | C. GitHub Actions |
|---|---|---|---|
| 보이는 것 | **앱 전체가 실제로 동작** | 로직 검증 결과, 화면 미리보기 | 앱이 뜬 스크린샷 |
| 필요한 것 | PC + Android Studio | PC + JDK 17 | 아무것도 (브라우저만) |
| 준비 시간 | 30분~1시간 | 5분 | 0분 |
| 걸리는 시간 | 부팅 1~3분 | 1분 | 15분 |

셋 다 폰이 필요 없습니다. 개발 중이라면 A + B 를 같이 씁니다.

---

## A. 에뮬레이터에서 실제로 돌리기

### A-1. 하드웨어 가속부터

에뮬레이터가 부팅조차 못 끝내는 경우는 거의 전부 여기서 걸립니다.

| OS | 해야 할 일 |
|---|---|
| **윈도우** | "Windows 기능 켜기/끄기" → **Windows 하이퍼바이저 플랫폼** 체크 → 재부팅.<br>(Intel HAXM 은 폐지되었습니다. 옛 안내를 따라 HAXM 을 찾지 마세요.) |
| **맥 (Apple Silicon)** | 별도 설정 없음. 다만 시스템 이미지는 반드시 **arm64-v8a** 를 고릅니다. x86_64 이미지를 고르면 부팅이 안 되거나 기어갑니다. |
| **맥 (인텔)** | 별도 설정 없음. |
| **리눅스** | KVM 이 필요합니다.<br>`sudo apt install qemu-kvm && sudo usermod -aG kvm $USER` 후 로그아웃/로그인. |

확인:

```bash
emulator -accel-check
```

`accel: 0 ... is installed and usable` 이 나오면 준비된 것입니다.

### A-2. AVD(가상 기기) 만들기

Android Studio → **Device Manager** → **Create Device** →
Pixel 6 → 시스템 이미지 선택.

> ⚠️ **시스템 이미지는 반드시 "Google APIs" 또는 "Google Play" 를 고르세요.**
> 이 앱은 위치를 `FusedLocationProviderClient` 로 읽습니다. 이것은 Google Play
> services 가 제공하는 기능이라, AOSP 이미지(이름에 Google 이 없는 것)에서는
> **앱은 뜨지만 위치가 영영 잡히지 않습니다.** 화면은 홈에서 멈추고, 겉보기에는
> "데스크톱에서는 안 되는 앱"처럼 보입니다. 원인의 절반이 이것입니다.

API 레벨은 34 또는 35 를 권합니다(이 앱의 최소는 26).

명령줄로 만들려면:

```bash
sdkmanager "platform-tools" "emulator" "system-images;android-34;google_apis;x86_64"
avdmanager create avd -n eodiga -k "system-images;android-34;google_apis;x86_64" -d pixel_6
emulator -avd eodiga
```

맥 Apple Silicon 이면 `x86_64` 를 `arm64-v8a` 로 바꿉니다.

### A-3. 앱을 넣고 띄우기 — 한 줄

에뮬레이터가 떠 있는 상태에서 저장소 최상위에서:

```bash
bash tools/emulator_smoke.sh
```

이 스크립트가 하는 일:

1. 디버그 APK 를 찾고, 없으면 `./gradlew assembleDebug` 로 빌드
2. 화면 잠금 해제 후 설치
3. 앱이 시작할 때 묻는 권한(위치·알림)만 미리 허용 — 대화상자를 건너뜁니다
4. 앱 실행 + 시작 좌표 주입 + 스크린샷 저장
5. 죽었는지(`FATAL EXCEPTION`), 화면이 실제로 앞에 떠 있는지 확인

결과는 `build/desktop-smoke/` 에 스크린샷과 `logcat.txt` 로 남습니다.
앱이 죽거나 화면에 뜨지 못하면 종료 코드 1 로 실패하므로, 그대로 CI 에도 씁니다.

APK 를 이미 받아 두었다면(예: Actions 에서 내려받은 것):

```bash
bash tools/emulator_smoke.sh --no-build --apk ~/Downloads/app-debug.apk
```

수동으로 하고 싶다면 이게 전부입니다:

```bash
./gradlew installDebug
adb shell pm grant kr.eodiga.wayfinder.debug android.permission.ACCESS_FINE_LOCATION
adb shell pm grant kr.eodiga.wayfinder.debug android.permission.POST_NOTIFICATIONS
adb shell am start -n kr.eodiga.wayfinder.debug/kr.eodiga.wayfinder.MainActivity
```

> 디버그 빌드의 패키지 이름에는 `.debug` 가 붙습니다(`applicationIdSuffix`).
> `kr.eodiga.wayfinder` 로 명령하면 "패키지 없음" 이 납니다.

### A-4. 가짜로 걷게 만들기 — 여기가 핵심

좌표가 멈춰 있으면 안내도 멈춰 있습니다. 이동 경로를 만들어 넣습니다.

```bash
# 집 → 걸어서 정류장 → 버스 → 걸어서 목적지, 11분짜리 경로
python3 tools/make_demo_gpx.py --out demo.gpx
```

넣는 방법은 셋 중 편한 것으로:

**① 에뮬레이터 창에서 (가장 편함)**
에뮬레이터 오른쪽 `⋯`(Extended controls) → **Location** → **Routes/Import GPX** →
`demo.gpx` 열기 → **Play**. 재생 속도도 그 화면에서 바꿉니다.

**② 창 없이 명령줄로**

```bash
python3 tools/make_demo_gpx.py --format sh --speed 10 --out demo.sh
bash demo.sh          # adb emu geo fix 를 시간표대로 뿌립니다
```

**③ 스모크 스크립트에 붙여서 한 번에**

```bash
python3 tools/make_demo_gpx.py --format sh --speed 10 --out demo.sh
bash tools/emulator_smoke.sh --route demo.sh
```

좌표 한 점만 던질 수도 있습니다. `geo fix` 는 **경도가 먼저**입니다:

```bash
adb emu geo fix 126.92900 37.61000
```

내 동네로 바꾸려면 구간을 직접 줍니다. 종류는 `walk` / `wait` / `bus`:

```bash
python3 tools/make_demo_gpx.py \
  --leg walk:37.6100,126.9290 \
  --leg wait:37.6086,126.9305 \
  --leg bus:37.6011,126.9412 \
  --leg walk:37.6004,126.9425 \
  --out 우리동네.gpx
```

`--wait-seconds` 는 정류장에 서 있는 시간입니다. 도착정보 폴링과 음성 안내가
나오는 것을 볼 시간을 벌어 줍니다.

### A-5. 안내 음성이 나오는지

에뮬레이터의 소리는 PC 스피커로 나옵니다. 다만 **한국어 음성 데이터가 기본으로
깔려 있지 않은 이미지**가 있습니다. 이 경우 화면은 정상인데 아무 말도 안 합니다.

에뮬레이터 안에서: 설정 → 시스템 → 언어 및 입력 → **텍스트 음성 변환 출력** →
엔진 톱니 → **음성 데이터 설치** → 한국어.

문안 자체만 귀로 확인하려면 에뮬레이터가 필요 없습니다.
[`docs/original-mockup.html`](original-mockup.html) 을 브라우저로 열면 실제 안내
음성이 그대로 나옵니다.

### A-6. 화면이 꺼져도 안내가 이어지는지

이 앱에서 가장 중요한 동작입니다. 어르신은 버스에 타면 폰을 주머니에 넣습니다.

```bash
adb shell input keyevent KEYCODE_POWER          # 화면 끄기
adb shell dumpsys activity services kr.eodiga.wayfinder.debug | grep -i journey
```

`JourneyGuidanceService` 가 살아 있으면 성공입니다. 알림이 떠 있는지는:

```bash
adb shell dumpsys notification --noredact | grep -i eodiga
```

### A-7. 에뮬레이터에서 안 되는 것들

정직하게 적어 둡니다. 아래는 에뮬레이터의 한계이지 앱의 문제가 아닙니다.

| 기능 | 에뮬레이터에서 | 대신 확인하는 법 |
|---|---|---|
| 진동 | 느낄 수 없음(모터가 없음) | 진동 호출 시점은 `JourneyControllerTest` 가 검증 |
| 보호자 문자 | 실제로 발송되지 않음 | 문자 앱으로 넘어가는 대체 경로를 눈으로 확인 |
| 전화 걸기 | 실제 통화 불가 | 전화 화면까지 뜨는지 확인 |
| 음성 입력 | 마이크가 없으면 인식 안 됨 | 에뮬레이터 설정에서 호스트 마이크를 켜거나, 검색어 직접 입력 |
| **승차 후 정거장 진행** | **재현 불가** | 아래 참고 |

마지막 항목이 중요합니다. 하차 알림은 GPS 가 아니라 **실시간 버스 차량 위치
(nodeord)** 로 계산합니다. 실제 버스가 도로 위를 달리고 있어야 값이 변합니다.
즉 "2 정거장 남았습니다 → 벨을 누르세요 → 내리세요" 구간은 에뮬레이터로 만들 수
없고, 실제 노선이 운행하는 시간대에 그 정류장에서만 볼 수 있습니다.

그래서 이 구간은 단위 테스트로 검증합니다(B 항목). 하차 판정, 차량을 놓쳤을 때의
처리, 안내 순서가 전부 테스트로 고정되어 있습니다.

### A-8. 인증키

키가 없어도 앱은 설치·실행되고 화면도 전부 뜨지만, 정류장·버스 조회가 실패하고
"인증키 없음" 안내가 나옵니다. 실데이터를 보려면 `local.properties` 에 키를 넣고
다시 빌드해야 합니다. 발급 방법은 [`docs/install.md`](install.md).

---

## B. 기기 없이, PC 만으로

에뮬레이터조차 띄우지 않고 확인하는 방법입니다. 빠르기 때문에 개발 중에는
이쪽을 훨씬 자주 씁니다.

### 단위 테스트

```bash
./gradlew test
```

95개의 테스트가 JVM 위에서 몇 초 만에 돕니다. 에뮬레이터로 재현할 수 없는 부분,
특히 **하차 알림 판정**과 **음성 문안**이 여기에 들어 있습니다:

| 테스트 | 무엇을 지키나 |
|---|---|
| `JourneyControllerTest` | 하차 알림 시점, 탄 차량을 놓쳤을 때의 안내 |
| `RoutePlannerTest` | 진행 방향이 맞는 노선만 고르는지 |
| `SpeechTextTest`, `KoreanTextTest` | 숫자·조사가 귀로 들었을 때 맞는지 |
| `GuardianNotifierTest` | 길을 잃었을 때 보호자에게 가는 경로 |
| `TransitRepositoryTest`, `TagoItemsAdapterTest` | 공공데이터 응답의 변덕을 견디는지 |

### 화면을 눈으로 보기

Android Studio 에서 화면 파일을 열면 **미리보기(Preview)** 가 PC 화면에 그대로
렌더링됩니다. 기기도 에뮬레이터도 필요 없습니다.

화면 흐름 전체를 클릭해 보고 싶다면
[`docs/original-mockup.html`](original-mockup.html) 을 브라우저로 엽니다.
11개 화면이 그대로 있고 **안내 음성도 실제로 나옵니다.**

---

## C. PC 도 없을 때 — GitHub Actions

브라우저만으로 확인하는 경로입니다. 저장소 **Actions** 탭에서 직접 실행합니다.

| 워크플로 | 결과 |
|---|---|
| **디버그 APK 빌드** | 설치용 APK (`.github/workflows/build-apk.yml`) |
| **에뮬레이터 구동 시험** | GitHub 서버의 리눅스에서 에뮬레이터를 띄워 앱을 실행하고, **스크린샷과 logcat 을 첨부** (`.github/workflows/emulator-smoke.yml`) |

두 번째 것이 "내 PC 가 아니어도 앱이 실제로 뜬다"는 증거입니다. 실행 요약 화면
맨 아래 **Artifacts** 에서 `emulator-smoke` 를 내려받으면 앱이 뜬 화면과 전체
로그가 들어 있습니다. 앱이 죽으면 워크플로가 빨간색으로 실패합니다.

---

## 자주 막히는 곳

| 증상 | 원인 | 조치 |
|---|---|---|
| 에뮬레이터가 부팅하다 멈춤 | 하드웨어 가속 없음 | A-1 |
| `x86_64 emulation currently requires hardware acceleration` | 같음 | A-1 |
| 맥에서 지독하게 느림 | Apple Silicon 인데 x86_64 이미지 | arm64-v8a 이미지로 AVD 재생성 |
| 앱은 뜨는데 위치를 못 잡음 | AOSP 시스템 이미지 | Google APIs 이미지로 재생성 (A-2) |
| 위치를 넣었는데 화면이 안 넘어감 | 좌표가 한 점에 고정 | 경로 재생 (A-4) |
| 좌표를 넣었는데 엉뚱한 곳 | `geo fix` 는 **경도 먼저** | `adb emu geo fix <경도> <위도>` |
| 화면은 되는데 무음 | 한국어 TTS 데이터 없음 | A-5 |
| `Failure [INSTALL_FAILED_...]` | 기존 설치본과 서명 충돌 | `adb uninstall kr.eodiga.wayfinder.debug` 후 재설치 |
| `package not found` | `.debug` 접미사 누락 | 패키지는 `kr.eodiga.wayfinder.debug` |
| 화면마다 "인증키 없음" | 키 미설정 | A-8 |
| `adb: command not found` | platform-tools 가 PATH 에 없음 | 윈도우 `%LOCALAPPDATA%\Android\Sdk\platform-tools`,<br>맥·리눅스 `~/Android/Sdk/platform-tools` |
