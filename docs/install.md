# 폰에 설치하기

이 앱은 스토어에 올라가 있지 않습니다. 직접 빌드해서 설치해야 합니다.
**아이폰에서는 설치할 수 없습니다** — 안드로이드 전용 앱입니다.

경로는 두 가지입니다.

| | A. GitHub Actions | B. PC 에서 빌드 |
|---|---|---|
| 필요한 것 | 폰만 | PC + Android Studio |
| 걸리는 시간 | 최초 10분, 이후 5분 | 최초 30분~1시간 |
| 코드 고치며 확인 | 매번 푸시해야 함 | 즉시 |
| 추천 | **폰만 있을 때** | 개발할 때 |

---

## 준비: 공공데이터 인증키

두 경로 모두 인증키가 필요합니다. 없어도 앱은 설치·실행되지만
정류장·버스 조회가 전부 실패하고 "인증키 없음" 안내만 뜹니다.

1. [data.go.kr](https://www.data.go.kr) 회원가입 (폰 브라우저에서 가능)
2. 아래 데이터셋을 각각 검색해 **활용신청** — 버스 4종은 보통 즉시 승인됩니다.
   - 국토교통부 TAGO_버스정류소정보
   - 국토교통부 TAGO_버스도착정보
   - 국토교통부 TAGO_버스노선정보
   - 국토교통부 TAGO_버스위치정보
   - 국립중앙의료원 전국 병·의원 찾기
   - 기상청 단기예보 조회서비스
3. 마이페이지 → 오픈API → 인증키 발급 현황 → **일반 인증키(Decoding)** 복사

> ⚠️ **Encoding 키가 아니라 Decoding 키**입니다.
> `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` 의 대부분은 이 문제입니다.
> Encoding 키를 넣으면 `+` 가 `%2B` 로 이중 인코딩되어 서버가 키를 못 알아봅니다.

---

## A. 폰만으로 — GitHub Actions 에서 APK 받기

PC 없이 APK 를 얻는 유일한 방법입니다.
빌드는 GitHub 서버가 대신 돌리고, 결과물만 폰으로 내려받습니다.

### A-1. 인증키를 저장소 Secret 에 등록

폰 브라우저에서 GitHub 저장소를 엽니다.

1. **Settings** → 왼쪽 **Secrets and variables** → **Actions**
2. **New repository secret**
3. Name 에 `PUBLIC_DATA_SERVICE_KEY`, Secret 에 발급받은 Decoding 키를 붙여넣고 저장

`JUSO_CONFIRM_KEY`, `SEOUL_OPEN_API_KEY` 는 선택입니다.
없으면 주소검색·지하철 실시간 도착만 꺼진 채로 나머지는 동작합니다.

> Secret 은 등록 후 다시 볼 수 없고 빌드 로그에도 마스킹되어 찍힙니다.
> 키가 APK 안에는 들어가므로, **개인용으로만 쓰고 APK 를 남에게 돌리지 마세요.**

### A-2. 빌드 실행

1. 저장소 상단 **Actions** 탭
2. 왼쪽 목록에서 **디버그 APK 빌드**
3. 오른쪽 **Run workflow** → 브랜치 고르고 초록 버튼

이 브랜치에 푸시하면 자동으로도 돕니다. 10분쯤 걸립니다.

### A-3. APK 내려받아 설치

1. 실행이 끝나면 그 실행 화면으로 들어갑니다 (초록 체크 표시)
2. 화면 맨 아래 **Artifacts** → `eodiga-debug-apk` 를 탭하면 zip 이 받아집니다
3. 폰의 **파일** 앱에서 zip 을 풀고 `app-debug.apk` 를 탭
4. "이 출처의 앱 설치" 를 묻는 화면이 뜨면 허용 → 설치

> zip 이 아니라 APK 를 바로 받고 싶어도, GitHub Artifacts 는 항상 zip 으로
> 감싸서 내려줍니다. 삼성 내장 파일 앱, Files by Google 모두 압축을 풀 수 있습니다.

> 설치 화면에서 Play 프로텍트 경고가 뜰 수 있습니다. 스토어를 거치지 않은
> 앱이면 항상 뜨는 경고입니다. "무시하고 설치" 로 진행합니다.

---

## B. PC 에서 빌드해 설치

### B-1. 저장소 받기

```bash
git clone https://github.com/tkddls8848/pathfinder.git
cd pathfinder
```

### B-2. 설정 파일

```bash
cp local.properties.example local.properties
```

`local.properties` 를 열어 채웁니다:

```properties
sdk.dir=/path/to/Android/sdk
PUBLIC_DATA_SERVICE_KEY=발급받은_디코딩_인증키
```

Android Studio 로 프로젝트를 한 번 열면 `sdk.dir` 은 자동으로 채워집니다.
이 파일은 `.gitignore` 에 있어 커밋되지 않습니다.

### B-3. 폰을 USB 로 연결

1. 폰 **설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드번호**를 7번 연타
   → "개발자가 되셨습니다"
2. **설정 → 개발자 옵션 → USB 디버깅** 켜기
3. USB 로 PC 에 연결, 폰에 뜨는 "USB 디버깅을 허용하시겠습니까?" 허용

```bash
adb devices     # 기기가 목록에 보이면 준비 완료
```

### B-4. 빌드·설치

```bash
./gradlew installDebug
```

APK 파일만 필요하면:

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

**요구사항**: JDK 17, Android SDK 35. 폰은 Android 8.0(minSdk 26) 이상.

---

## 설치 후 첫 실행

디버그 빌드는 `applicationId` 에 `.debug` 가 붙습니다
(`kr.eodiga.wayfinder.debug`). 정식 빌드와 함께 깔아도 충돌하지 않습니다.

1. **위치 권한** — "항상 허용" 을 선택해야 화면이 꺼진 뒤에도 하차 알림이 뜹니다.
   최초에는 "앱 사용 중에만 허용" 만 뜨는 경우가 있는데,
   그때는 설정 → 앱 → 어디가요 → 권한 → 위치 → **항상 허용** 으로 바꿔주세요.
2. **알림 권한** — 안내 진행 상황과 하차 알림이 이걸로 나갑니다. 거부하면 안내가 보이지 않습니다.
3. **설정 → 가족 추가** — 길을 잃었을 때 연락할 보호자
4. **설정 → 자주 가는 곳 추가** — 집, 병원 등

문자·전화 권한은 선택입니다. 거부해도 "길을 잃었어요" 는 동작하며,
직접 보내는 대신 문자 앱·전화 앱을 열어주는 방식으로 바뀝니다.

### 배터리 최적화 제외

버스 안에서 폰을 주머니에 넣으면 제조사 배터리 절약 기능이
포그라운드 서비스를 잠재울 수 있습니다. 특히 삼성·샤오미에서 잦습니다.

설정 → 배터리 → 백그라운드 사용 제한 (또는 앱 → 어디가요 → 배터리)
→ **제한 없음 / 최적화 안 함** 으로 바꿔주세요.

---

## 잘 안 될 때

| 증상 | 원인 |
|---|---|
| `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` | Encoding 키를 넣었거나, 해당 데이터셋 활용신청이 아직 승인되지 않음 |
| 정류장이 하나도 안 뜸 | 위치 권한 거부, 또는 실내라 GPS 가 안 잡힘 |
| 화면 끄면 안내가 멈춤 | 위치 권한이 "항상 허용" 이 아니거나 배터리 최적화에 걸림 |
| 설치 화면에서 막힘 | "출처를 알 수 없는 앱" 허용 필요. Android 8 부터는 앱별로 허용합니다 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 서명이 다른 같은 앱이 이미 설치됨. 지우고 다시 설치 |
| Actions 빌드가 빨간 X | 실행 화면에서 실패한 단계를 펼치면 이유가 나옵니다 |
