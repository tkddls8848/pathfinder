# 공공데이터 연계 명세

최종 갱신: 2026-10-13

이 앱이 쓰는 공공 데이터와, 왜 그 데이터를 그렇게 쓰는지를 정리한 문서입니다.

---

## 1. 사용 중인 API

| # | 데이터셋 | 제공기관 | 용도 | 상태 |
|---|---------|---------|------|------|
| 1 | [버스정류소정보](https://www.data.go.kr/data/15098534/openapi.do) | 국토교통부 (TAGO) | 주변 정류소 탐색, 경유 노선 조회 | ✅ 실응답 확인 |
| 2 | [버스도착정보](https://www.data.go.kr/data/15098530/openapi.do) | 국토교통부 (TAGO) | 특정 노선 실시간 도착 예정, 저상버스 여부 | ✅ 실응답 확인 |
| 3 | [버스노선정보](https://www.data.go.kr/data/15098529/openapi.do) | 국토교통부 (TAGO) | 노선별 경유 정류소·순번 (진행 방향 검증) | ✅ 실응답 확인 |
| 4 | [버스위치정보](https://www.data.go.kr/data/15098533/openapi.do) | 국토교통부 (TAGO) | 실시간 차량 위치 → 하차 알림 | ✅ 실응답 확인 |
| 5 | [전국 병·의원 찾기](https://www.data.go.kr/data/15000736/openapi.do) | 국립중앙의료원 | 목적지(병원) 검색 | ✅ 한글 필터 동작 (2026-10-14 재확인, 2-7) |
| 6 | [단기예보 조회서비스](https://www.data.go.kr/data/15084084/openapi.do) | 기상청 | 출발 전 날씨 경고 | ✅ 실응답 확인 |
| 7 | [도로명주소 검색/좌표변환](https://business.juso.go.kr) | 행정안전부 | 일반 목적지 검색 | ❌ 승인키 미발급 |

1·2·3·4번은 앱에서 경로 한 건을 끝까지 만드는 데까지 확인했습니다.

**2026-08-30 재확인.** 윈도우 에뮬레이터에서 대전 둔산동 → 충남대학교병원을 조회해
`시청환승지에서 203번 4정거장 → 이마트 환승 → 315번 13정거장`, 모두 약 40분짜리
경로를 실시간 데이터로 받았습니다. 대안 경로 두 개(707+317 49분, 707+30 55분)와
도착정보 폴링까지 함께 동작합니다. 5·7번의 제약은 아래 그대로입니다.

### 인증키

1~6번은 공공데이터포털(data.go.kr) 인증키 **하나**로 조회합니다.
7번만 [주소기반산업지원서비스](https://business.juso.go.kr)에서 별도 승인키를 받습니다.

```properties
# local.properties
PUBLIC_DATA_SERVICE_KEY=<일반 인증키(Decoding)>
JUSO_CONFIRM_KEY=<도로명주소 승인키>
```

**키가 하나여도 활용신청은 데이터셋마다 따로 해야 합니다.** 키를 넣었는데 일부만
동작하는 상태가 정상적으로 발생합니다. 같은 기관(국립중앙의료원) 안에서도
서비스별로 갈립니다 — `HsptlAsembySearchService` 는 열려 있는데
`ErmctInfoInqireService` 는 미등록인 상태를 실제로 봤습니다.

`마이페이지 → 오픈API → 활용신청 현황` 에서 확인합니다. 버스 4종은 자동 승인입니다.

### 두 오류를 구분하세요

포털이 내리는 오류 두 개가 헷갈리기 쉽지만 원인이 전혀 다릅니다.
**이 구분이 진단의 출발점입니다.**

| 오류 | 뜻 | 봐야 할 곳 |
|---|---|---|
| `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` | 경로는 맞다. 그 데이터셋에 **신청이 안 됐다** | 포털 활용신청 현황 |
| `NO_OPENAPI_SERVICE_ERROR` | 신청과 무관하다. **URL 이 틀렸다** | 기관코드·오퍼레이션 이름 (2-6) |

전자가 돌아온다면 URL 은 옳다는 뜻이기도 합니다. 승인 전에도 경로 검증은 됩니다.

> `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` 의 또 다른 원인은 **Encoding 키**입니다.
> 이미 퍼센트 인코딩된 키를 넣으면 HTTP 클라이언트가 한 번 더 인코딩해
> `%2B` → `%252B` 가 됩니다. `ServiceKeyProvider` 가 어떤 형태를 넣든 raw 로
> 정규화하지만, 원칙적으로는 **Decoding 키**를 넣으세요.

---

## 2. 실제 응답에서 부딪히는 문제들

문서에는 안 나오지만 실사용에서 반드시 터지는 것들입니다.
모두 `TagoEnvelope.kt` / `LenientString.kt` 에서 처리하고, 테스트로 고정해 두었습니다.

### 2-1. `items` 가 세 가지 모양으로 온다

```jsonc
// 결과 0건 — 객체가 아니라 빈 문자열!
{ "body": { "items": "", "totalCount": 0 } }

// 결과 1건 — 배열이 아니라 단일 객체!
{ "body": { "items": { "item": { "nodeid": "..." } }, "totalCount": 1 } }

// 결과 N건
{ "body": { "items": { "item": [ … ] }, "totalCount": 12 } }
```

막차 이후나 정류소가 하나뿐인 지역에서 0건·1건이 실제로 발생합니다.
배열만 가정하고 짜면 그 순간 앱이 죽습니다.

### 2-2. 같은 필드가 지자체마다 타입이 다르다

`vehicleno`, `routeno` 는 어떤 지자체에서는 문자열, 다른 곳에서는 숫자로 옵니다.
`@LenientString` 한정자가 둘 다 String 으로 받습니다.

### 2-3. HTTP 200 인데 업무 오류

공공데이터포털은 인증 실패·할당량 초과도 HTTP 200 으로 내려보내고,
`header.resultCode` 에만 표시합니다. `itemsOrThrow()` 가 이를 예외로 승격시킵니다.

| resultCode | 의미 | 앱 동작 |
|---|---|---|
| `00` | 정상 | — |
| `20`, `30`, `31`, `32` | 인증키 문제 | 재시도하지 않음. "가족에게 알려주세요" 안내 |
| `22` | 일일 트래픽 초과 | 재시도하지 않음 |
| 그 외 | 서버 오류 | 지수 백오프 재시도 (0.4s → 0.8s → 1.6s) |

### 2-4. `_type=json` 을 빼먹으면 XML 이 온다

기본 응답 포맷이 XML 입니다. `PublicDataInterceptor` 가 모든 요청에 강제로 붙입니다.

### 2-5. 응답 DTO는 실제 소비 필드만 유지한다

공공데이터 응답에는 정류소 번호, 노선 기종점, 첫차·막차 시각, 차량 좌표처럼 현재 기능이
읽지 않는 필드도 많습니다. Moshi는 선언하지 않은 필드를 안전하게 무시하므로 DTO와 도메인에는
경로 탐색·도착 안내·하차 계산에 실제로 쓰는 값만 둡니다. 새 기능이 해당 값을 소비할 때
필드와 테스트를 함께 추가합니다.

### 2-6. URL 이 틀리면 인증 오류처럼 보인다

이 프로젝트에서 **같은 종류의 버그를 두 번** 만났습니다. 둘 다 몇 시간을 잡아먹었고
둘 다 인증 문제로 보였습니다.

**기관코드 누락.** 포털은 호스트가 하나이고 기관코드로 서비스를 가릅니다.

```
https://apis.data.go.kr/B552657/HsptlAsembySearchService/...
                        ^^^^^^^ 이게 빠지면 NO_OPENAPI_SERVICE_ERROR
```

베이스 URL 상수 이름에 어느 기관인지 남기세요. `DATA_GO_KR_BASE` 처럼 뭉뚱그리면
기관코드를 빠뜨리기 쉽습니다. 실제로 그렇게 빠져 있었습니다.

| 상수 | 값 |
|---|---|
| `TAGO_BASE` | `https://apis.data.go.kr/1613000/` |
| `HOSPITAL_BASE` | `https://apis.data.go.kr/B552657/` |
| `WEATHER_BASE` | `https://apis.data.go.kr/1360000/` |

**오퍼레이션 이름.** 활용가이드의 이름을 글자 그대로 옮겨야 합니다.
줄임말이 규칙적이지 않아 눈으로는 맞아 보입니다.

```
getRouteAcctoSttnList        ← 이런 이름은 없다
getRouteAcctoThrghSttnList   ← 경유 = Thrgh
```

현재 쓰는 다섯 개는 전부 실응답으로 대조했습니다.

| 서비스 | 오퍼레이션 |
|---|---|
| `BusSttnInfoInqireService` | `getCrdntPrxmtSttnList` · `getSttnThrghRouteList` |
| `BusRouteInfoInqireService` | `getRouteAcctoThrghSttnList` |
| `ArvlInfoInqireService` | `getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList` |
| `BusLcInfoInqireService` | `getRouteAcctoBusLcList` |

### 2-7. 병·의원 API 의 한글 필터 — 2026-08 에는 0건, 2026-10 에는 동작

> **2026-10-14 재확인: 지금은 동작합니다.** 앱과 같은 1회 인코딩으로 불렀습니다.
>
> ```
> 필터 없음 → 79,015건   Q0=서울특별시 → 19,486건   Q0=서울특별시&Q1=중구 → 593건
> Q0=강원특별자치도&Q1=인제군 → 28건   QN=서울의료원 → 1건(서울특별시서울의료원)
> QN=성모 → 1,058건   Q0=서울특별시&QN=성모 → 349건
> ```
>
> `QN` 은 부분일치입니다. 아래의 2026-08 결과와 요청 방식이 같으므로 서버 쪽 동작이 바뀐 것으로
> 봅니다. 아래 기록은 경위로 남깁니다. 목적지 검색은 반경 검색이 되는 심평원(§9-3)으로 옮길
> 계획이라([`metro-api-plan.md`](metro-api-plan.md) 1단계) 이 API 는 그 뒤 빠집니다.

**2026-08 기록.**

`HsptlAsembySearchService/getHsptlMdcncListInfoInqire` 는 값의 종류에 따라 갈립니다.

```
필터 없음        → 78,689건   영문 코드 QZ=B → 1,432건   QD=D001 → 23,675건
Q0=서울특별시    →      0건   Q1=중구 → 0건   QN=<정확한 기관명> → 0건
```

인코딩을 네 가지로 시험했습니다 — UTF-8, UTF-8 이중, EUC-KR, 인코딩 없음.
**전부 0건입니다.** 응답 데이터에 실제로 들어 있는 시도명을 그대로 넣어도 0이므로
값이 틀린 것이 아닙니다. 서버 쪽 동작으로 판단합니다.

그때는 이 API 로 이름·지역 검색을 할 수 없다고 판단했습니다.

> **보탬 (2026-10-13).** 다른 프로젝트가 같은 연산에서 `Q0=강원특별자치도`·`Q1=인제군` 으로
> 29건을 받았다는 실측이 있습니다(2026-09, 2차 근거). 그래서 앱의 이중 인코딩을 먼저 의심했지만,
> 코드로는 아닙니다. `HospitalApi` 는 Retrofit `@Query`(인코딩 1회)로 보내고,
> `PublicDataInterceptor` 는 `serviceKey`·`_type` 만 덧붙입니다. 그쪽도 `URLSearchParams`
> (인코딩 1회)와 `_type=json` 을 씁니다. 보내는 방식은 같습니다.
> 남은 가설은 값(서울·중구 vs 강원·인제)이나 시점(2026-08 vs 2026-09)에 따라 서버 동작이 다르다는
> 것이었고, 2026-10-14 실호출에서 서울·강원이 모두 동작해 시점 쪽으로 정리됐습니다(위 상자).

---

## 2-8. 서울에는 TAGO 버스 데이터가 없다

좌표만 바꿔 `getCrdntPrxmtSttnList` 를 호출한 결과입니다.

```
대전시청 → 28건    부산시청 → 19건    수원시청 → 15건
서울시청 →  0건
```

서울은 TAGO 에 버스 데이터를 넣지 않고 자체 API 를 운영합니다.
그 자체 API 의 명세는 [§8](#8-서울-버스-api-명세) 에 정리했습니다 — 오퍼레이션은
지금 쓰는 TAGO 5종과 1:1 로 대응하지만 **그대로 갈아끼울 수는 없습니다.**

연동 배선은 아직 하나도 없습니다. `local.properties` 에 `SEOUL_OPEN_API_KEY=` 줄만
남아 있고, 그 값을 앱으로 넘기는 `buildConfigField` 는 `build.gradle.kts` 에 없습니다.
값을 채워도 도달할 경로가 없다는 뜻입니다.

**결과: 이 앱은 현재 서울에서 동작하지 않습니다.** 개발 중 위치를 서울로 목킹하면
모든 조회가 0건이 되어 인증 문제로 오인하기 쉽습니다. 대전·부산·수원 좌표로 시험하세요.

**지금은 화면에 "여기서는 버스로 가는 길을 찾지 못했습니다" 가 나옵니다.**
정류소가 0건이면 `RoutePlanner` 가 도보 안내로 폴백하는데, 직선 2km(`MAX_WALK_FALLBACK_M`)를
넘으면 도보 안내 대신 빈 결과를 돌려주고 화면은 `FailureReason.NoRoute` 로 갈립니다.
상한이 없던 때는 서울에서 대전 목적지를 고르면 **"걸어서 3336분"(약 55시간)** 이
그대로 안내됐습니다(2026-08-30 에뮬레이터 실측).

---

## 3. 경로 탐색을 직접 구현한 이유

TAGO 는 정류소·노선·도착·위치 네 가지 원본 데이터만 줍니다. "출발지 → 목적지 환승 경로"를
주는 연산이 없습니다.

> **정정 (2026-10-13).** 처음에는 "공공데이터포털 전체에 환승 경로 API 가 없다"고 적었는데
> 틀렸습니다. 서울시 대중교통환승경로 조회(15000414)가 버스·지하철 환승 경로를 줍니다.
> 서울시 API 지만 서울 → 수원 경로도 경기 버스를 섞어 줍니다(2026-10-14 실호출). 명세와 실측은
> [§9-2](#9-2-서울-대중교통환승경로-15000414) · [§9-5](#9-5-실호출-결과-2026-10-14)에 있습니다. 쓰더라도 아래 4번 단계(진행 방향 검증)는 그대로 거칩니다.

그래서 `RoutePlanner` 가 원본 데이터를 조합해 경로를 만듭니다.

```
1. 출발지·목적지 반경 500m 정류소 조회      … 버스정류소정보 (getCrdntPrxmtSttnList)
2. 각 정류소 경유 노선 조회                  … 버스정류소정보 (getSttnThrghRouteList)
3. 출발쪽 노선 ∩ 도착쪽 노선 = 직통 후보
4. 노선별 경유 정류소 순번으로 진행 방향 검증  … 버스노선정보 (getRouteAcctoThrghSttnList)
       ⚠ 승차순번 < 하차순번 이어야 한다
5. 직통이 없으면 1회 환승까지 탐색
6. "체감 난이도" 순으로 정렬
```

### 4번 단계가 핵심입니다

정류소 이름만 맞춰 경로를 만들면 **반대 방향 정류장으로 안내**하게 됩니다.
어르신에게 이건 단순한 불편이 아니라 낯선 동네에 내려 길을 잃는 사고입니다.
그래서 노선의 `nodeord`(경유 순번)로 방향을 반드시 검증하고,
`RoutePlannerTest` 에 회귀 테스트로 고정했습니다.

### 체감 난이도 = 최단 시간이 아니다

```kotlin
난이도 = 도보시간 × 1.6  +  버스시간  +  환승횟수 × 8분
```

- 환승 1회에 **8분** 페널티 — 계단·대기·"내가 맞게 탄 건가" 하는 불안의 값
- 도보 1분은 버스 안 1분의 **1.6배** — 앉아 있는 것과 걷는 것은 다르다
- 도보 속도 **0.75 m/s** — 국토부 교통약자 기준(0.8)보다 보수적으로

30분 걸려도 환승 없는 경로가, 22분 걸리지만 두 번 갈아타는 경로보다 낫습니다.

### 미해결 — 난이도 식이 긴 노선을 거르지 못한다

첫 실통신 경로 탐색에서 직선 12km 구간에 **86정거장·179분** 짜리 경로가 나왔습니다.

```
708번 · 57정거장  →  119번 · 29정거장
```

179분은 "정거장당 2분 추정"(5절의 알려진 한계)을 86에 곱한 값이라 추정식 문제가
아닙니다. 문제는 86정거장 자체입니다. 위 식은 환승만 8분으로 벌하고 **노선 길이에는
상한이 없습니다.** 돌아가는 지선을 뽑아도 걸러지지 않습니다.

승차~하차 구간이 아니라 노선 전체를 세고 있을 가능성도 남아 있습니다.
`RoutePlanner` 의 정거장 수 계산과 후보 필터를 함께 봐야 합니다.

---

## 4. 하차 알림에 도착예정시간을 쓰지 않는 이유

`ArvlInfoInqireService` 의 `arrtime`(도착예정 초)은 정체 구간에서 크게 흔들립니다.
대신 `BusLcInfoInqireService` 의 **차량 순번(`nodeord`)** 으로 남은 정거장을 셉니다.

```
남은 정거장 = 하차정류소 순번 − 현재 차량 순번
```

예정 시간은 틀려도 정거장 수는 정확합니다.
하차 알림이 한 정거장만 빗나가도 어르신은 낯선 곳에 내리게 되므로,
이 앱에서 가장 신뢰도가 중요한 계산입니다.

승차 시 차량번호(`vehicleno`)를 잡아두면 같은 노선의 다른 차량과 섞이지 않습니다.

---

## 5. 트래픽 관리

개발계정 일일 한도는 **API 당 10,000건**입니다. 아무 생각 없이 짜면 하루도 못 갑니다.

| 대책 | 효과 |
|---|---|
| 노선별 경유 정류소 **메모리 캐시** | 경로 탐색 1회에 수십 번 호출되던 것을 노선당 1회로 |
| 도착정보 **적응형 폴링** | 도착 임박 8초 / 5분 이내 15초 / 그 외 30초 |
| 버스 도착 시 **폴링 즉시 중단** | 정류장에서 20분 대기 시 120회 → 약 50회 |
| 인증·할당량 오류는 **재시도 안 함** | 실패 폭주 방지 |

운영계정은 활용사례 등록 후 상향 신청할 수 있습니다.

---

## 6. 좌표계

도로명주소 좌표변환 API 는 WGS84 가 아니라 **EPSG:5179 (Korea 2000 / Unified CS, UTM-K)** 로
좌표를 줍니다. 지도 SDK 없이 쓰려면 앱에서 직접 역투영해야 합니다.

`Epsg5179.toWgs84()` 가 Snyder 의 역 횡단메르카토르 급수식으로 변환합니다.
(원점 38°N / 127.5°E, 축척 0.9996, 가산값 1,000,000 / 2,000,000, GRS80)

기상청 단기예보는 또 다른 좌표계를 씁니다 — 람베르트 정각원추도법 기반의
5km 격자 번호(`nx`, `ny`). `KmaGrid.toGrid()` 가 처리합니다.

---

## 7. 연동 예정 (Phase 2)

아래는 **데이터셋 존재는 확인했으나 엔드포인트 명세를 검증하지 못해** 코드에 넣지 않았습니다.
추측한 URL 을 넣으면 조용히 404 가 나므로, 활용가이드 확인 후 추가해야 합니다.

| 데이터셋 | 용도 |
|---|---|
| [서울교통공사_빠른하차정보](https://www.data.go.kr/data/15143840/openapi.do) | 엘리베이터와 가장 가까운 하차 칸·문 번호 |
| [서울시 지하철역 엘리베이터 위치정보](https://www.data.go.kr/data/15098158/openapi.do) | 지하철 구간 승강기 안내 (목업의 🛗 화면) |
| [서울교통공사_교통약자 이용시설 승강기 가동현황](https://data.seoul.go.kr/dataList/OA-15994/S/1/datasetView.do) | 고장난 엘리베이터 회피 |
| [서울특별시_지하철 실시간 도착정보](https://www.data.go.kr/data/15058052/openapi.do) | 지하철 실시간 도착 |

지하철을 넣으면 목업의 승강기 안내 화면이 살아나고,
계단을 피해야 하는 어르신에게 특히 가치가 큽니다.

---

## 8. 서울 버스 API 명세

[§2-8](#2-8-서울에는-tago-버스-데이터가-없다) 의 후속입니다. 2026-08-09 에
[레퍼런스](http://api.bus.go.kr/contents/sub02/svcList.html) 를 직접 받아 정리했습니다.
아직 **코드에 붙이지 않았습니다.** 붙이기 전에 읽어야 할 차이만 적습니다.

베이스 URL 은 `http://ws.bus.go.kr/api/rest/` 이고, 서비스마다 경로가 갈립니다.

| 앱이 하는 일 | TAGO (지금) | 서울 | 요청 변수 |
|---|---|---|---|
| 좌표 근접 정류소 | `getCrdntPrxmtSttnList` | `stationinfo/getStationByPos` | `tmX` `tmY` `radius`(0\~1500m) |
| 정류소 경유 노선 | `getSttnThrghRouteList` | `stationinfo/getRouteByStation` | **`arsId`** |
| 노선 경유 정류소 | `getRouteAcctoThrghSttnList` | `busRouteInfo/getStaionByRoute` | `busRouteId` |
| 정류소·노선 도착예정 | `getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList` | `arrive/getArrInfoByRoute` | `stId` `busRouteId` **`ord`** |
| 노선 차량 위치 | `getRouteAcctoBusLcList` | `buspos/getBusPosByRtid` | `busRouteId` |

기능은 전부 있습니다. 문제는 다섯 가지 차이입니다.

### 8-1. 기본은 XML, `resultType=json` 이면 JSON (2026-10-14 실호출 확인)

> **정정 (2026-10-13).** 처음에는 "XML 전용"이라고 적었습니다. api.bus.go.kr 레퍼런스에는
> 지금도 `Data format : XML` 만 있습니다. 그런데 data.go.kr 에 올라온 네 데이터셋의 활용가이드
> (2023-03 ~ 2025-09 개정판)는 모두 교환 데이터 표준을 `XML, JSON` 으로 표시하고
> 요청 변수 `resultType`(`xml`·`json`)을 적어 둡니다. 정리는 [§9-1](#9-1-서울-버스-4종) 에 있습니다.

TAGO 의 `_type=json`([§2-4](#2-4-_typejson-을-빼먹으면-xml-이-온다))과 **이름이 다릅니다.**
`PublicDataInterceptor` 가 붙이는 `_type` 은 서울 API 에서 의미가 없습니다.
2026-10-14 실호출에서 네 데이터셋 모두 `resultType=json` 으로 JSON 이 왔으므로(§9-5) **XML 컨버터는
필요 없습니다.** `resultType` 을 빼면 XML 이 옵니다.

### 8-2. 정류소 식별자가 두 개고, 오퍼레이션마다 요구하는 것이 다릅니다

```
getStationByPos    → stationId 와 arsId 를 둘 다 준다
getRouteByStation  → arsId 를 요구한다      ←
getArrInfoByRoute  → stId  를 요구한다      ←
getStaionByRoute   → station (정류소 고유 ID) 을 준다
```

`BusStop.nodeId` 하나로는 두 호출을 다 못 합니다. 둘을 같이 들고 다녀야 합니다.
좌표 조회가 둘 다 주므로 **정류소를 만들 때 한 번에 채워 두면** 됩니다.

### 8-3. 도착정보에 정류소 순번(`ord`)이 필수입니다

TAGO 는 `(cityCode, nodeId, routeId)` 로 충분했는데 서울은 그 정류소가 노선에서
몇 번째인지를 같이 넘겨야 합니다. `RoutePlanner` 가 진행 방향 검증([§3](#4번-단계가-핵심입니다))에서
이미 순번을 구하므로 값 자체는 있습니다. 다만 `TransitRepository.arrivalOf()` 시그니처가 바뀝니다.

### 8-4. 도착정보가 배열이 아니라 1·2번째 버스로 평탄화돼 있습니다

TAGO 는 `item` 배열인데 서울은 필드명 끝에 번호가 붙습니다.

```
vehId1 · plainNo1 · exps1 · busType1 · nstnOrd1 · isLast1 …   첫 번째 도착 버스
vehId2 · plainNo2 · exps2 · busType2 · nstnOrd2 · isLast2 …   두 번째
```

- 도착예정시간은 `exps`(지수평활, **초**)를 씁니다. `traTime`(여행시간)은 분 단위라 다른 값입니다.
- **저상버스 판정이 `busType` 입니다** — `0`:일반, `1`:저상, `2`:굴절.
  TAGO 의 `vehicletp` 문자열 대조와 다르므로 `BusKind.from()` 이 그대로는 안 먹습니다.

### 8-5. `cityCode` 가 없습니다

서울 단일이라 개념 자체가 없습니다. `BusStop.cityCode`·`BusRoute.cityCode` 가
서울 경로에서는 의미를 잃습니다. 지역에 따라 갈리는 자리를 만들 때 이 필드를
그대로 키로 쓰지 않는 편이 안전합니다.

### 함정 두 개

**`getStaionByRoute` 의 오타가 스펙입니다.** "Station" 이 아니라 **"Staion"** 입니다.
문서 원본이 그렇고 고쳐 쓰면 404 입니다. [§2-6](#2-6-url-이-틀리면-인증-오류처럼-보인다) 에
적은 `getRouteAcctoThrghSttnList` 사건과 정확히 같은 종류입니다. 같은 실수를 두 번 하지 마세요.

**에러 코드가 숫자입니다.** TAGO 의 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` 같은
문자열 판별이 그대로는 안 먹습니다. 응답 헤더의 `headerCd` 를 봅니다.

| | |
|---|---|
| `0` | 정상 |
| `4` | 결과 없음 — 오류가 아니라 빈 결과입니다 |
| `5` · `6` · `7` | 인증키 없음 · 미등록 · 사용중지 |
| `8` | 요청 제한 초과 |
| `20` | 잘못된 좌표 |

### 활용신청

data.go.kr 에서 **네 건을 각각** 신청합니다. 공공데이터포털 인증키와 같은 키를 씁니다.

| 데이터셋 | |
|---|---|
| [서울특별시_정류소정보조회](https://www.data.go.kr/data/15000303/openapi.do) | `stationinfo` |
| [서울특별시_노선정보조회](https://www.data.go.kr/data/15000193/openapi.do) | `busRouteInfo` |
| [서울특별시_버스도착정보조회](https://www.data.go.kr/data/15000314/openapi.do) | `arrive` |
| [서울특별시_버스위치정보조회](https://www.data.go.kr/data/15000332/openapi.do) | `buspos` |

---

## 9. 수도권 연동 0단계 (2026-10-13 ~ 14)

[`metro-api-plan.md`](metro-api-plan.md) §3 0단계의 결과입니다. 9-1 ~ 9-4 는 인증키 없이
확인한 것이고, 키로 부른 결과는 [9-5](#9-5-실호출-결과-2026-10-14)에 있습니다. 키 없이 확인한 것은 세 종류입니다.

- **경로 실측.** 게이트웨이(`apis.data.go.kr`)는 인증키보다 경로를 먼저 봅니다. 아무 키나 넣어
  `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` 가 오면 경로가 있다는 뜻이고, `NO_OPENAPI_SERVICE_ERROR`
  가 오면 없다는 뜻입니다([§1 두 오류를 구분하세요](#두-오류를-구분하세요)). 오퍼레이션 이름 한 글자만 바꿔도
  `NO_OPENAPI_SERVICE_ERROR` 로 바뀌는 것까지 확인했으므로 이 방법은 믿을 만합니다.
  `ws.bus.go.kr` 은 키를 먼저 보므로 이 방법이 통하지 않습니다(없는 경로도 401).
- **공식 명세 직접 열람.** data.go.kr 데이터셋 페이지, 그 페이지가 품은 Swagger, 첨부 활용가이드(docx),
  api.bus.go.kr 안내 페이지를 내려받아 읽었습니다.
- **코드 확인.** 앱 쪽 원인을 의심한 항목은 소스로 확인했습니다.

남은 확인은 `tools/probe_metro_apis.py` 가 키를 넣으면 한 번에 돌립니다.

```
python3 tools/probe_metro_apis.py --list     # 확인 목록
python3 tools/probe_metro_apis.py            # 전부. 원본 응답은 build/metro-probe/
```

### 9-1. 서울 버스 4종

| 확인 | 결과 | 근거 |
|---|---|---|
| https | **안 됩니다.** `ws.bus.go.kr:443` 에 TCP 연결이 안 됩니다(15초 타임아웃). 활용가이드 다섯 개 모두 전송 레벨 암호화 "없음" | 실측 · 공식 |
| JSON | 활용가이드 네 개 모두 `resultType=json` 을 적어 둡니다. data.go.kr 페이지의 데이터 포맷은 15000303·15000193·15000332 가 `JSON+XML`, 15000314(도착)만 `XML` 로 엇갈리지만 **네 개 모두 JSON 이 옵니다** | 공식 · 실호출(9-5) |
| 키 오류 모양 | 등록되지 않은 키는 `headerCd` 가 아니라 **HTTP 401** 과 봉투 없는 JSON 으로 옵니다. `resultType` 과 무관합니다 | 실측 |
| 상업 이용 | api.bus.go.kr 소개 페이지 원문: "당해 오픈 API 서비스는 상업적으로 이용 불가합니다." data.go.kr 은 공공누리 1유형(출처표시, 상업 이용 가능)이라 그대로 엇갈립니다 | 공식 |
| 요청 주소 | data.go.kr 에 적힌 주소가 §8 표와 같습니다(`getStaionByRoute` 오타 포함) | 공식 |

키 오류 응답은 이렇게 옵니다. §8 의 `headerCd` 분류만으로는 잡히지 않으므로 서울 응답 봉투는
HTTP 상태부터 봐야 합니다.

```
HTTP/1.1 401 Unauthorized
Content-Type: application/json;charset=utf-8

{"error":"Unauthorized","message":"유효하지 않은 서비스키입니다: 등록되지 않은 서비스키","status":401}
```

### 9-2. 서울 대중교통환승경로 15000414

[§3](#3-경로-탐색을-직접-구현한-이유) 의 정정입니다. 활용가이드(2021-11-16)를 기준으로 적습니다.

| 항목 | 값 |
|---|---|
| 주소 | `http://ws.bus.go.kr/api/rest/pathinfo/getPathInfoByBusNSub` (버스·지하철). 같은 경로에 `getPathInfoByBus`·`getPathInfoBySubway`·`getLocationInfo`(명칭 → 좌표) |
| 요청 | `startX`·`startY`·`endX`·`endY` (**WGS84**, x 가 경도), `resultType` (`xml`·`json`) |
| 한도 | 개발 1,000건, 개발·운영 모두 자동승인, 초당 30건 |
| 이용 조건 | 공공누리 1유형. 단 같은 서버라 9-1 의 "상업적 이용 불가" 문구가 함께 걸립니다 |
| 응답 | `itemList[]`(경로) 마다 `distance`·`time`(분)·`pathList[]`(구간). 구간은 `routeId`·`routeNm`, 탑승 `fid`·`fname`·`fx`·`fy`, 하차 `tid`·`tname`·`tx`·`ty`. 지하철 구간은 `railLinkList` 가 붙습니다 |
| 오류 코드 | 0 정상, 1 시스템 오류, 2 잘못된 쿼리, 3 정류소 없음, **4 노선 없음**, 5 잘못된 위치, 6 실시간 정보 없음, **7 경로 없음**, 8 운행 종료 |

- **`headerCd` 의 뜻이 서비스마다 다릅니다.** §8 표에서 `4` 는 "결과 없음"이고 `7` 은 키 사용중지인데,
  이 서비스에서는 `4` 가 노선 없음, `7` 이 경로 없음입니다. 서울 응답 봉투 하나로 묶을 때
  코드 해석은 서비스별로 둡니다.
- **진행 방향 검증에 쓸 값이 있습니다.** 활용가이드 예시에서 버스 구간의 `fid`·`tid` 는 9자리
  정류소 ID(`115000542`)라 `getStaionByRoute` 의 `station`·`seq` 와 맞춰 볼 수 있어 보입니다.
  지하철 구간의 `fid` 는 역 코드(`02330`)입니다.
- **경기·인천을 포함하는지는 문서에 없습니다.** 활용가이드에 범위 언급이 없고 data.go.kr 의
  공간범위도 비어 있습니다. 실호출에서 서울 → 수원은 경기 버스를 섞은 경로가 나왔습니다(9-5).

### 9-3. 심평원 병원·약국

| 확인 | 결과 | 근거 |
|---|---|---|
| 기관코드 | `B551182` 맞습니다 | 실측(경로) |
| 병원 | `B551182/hospInfoServicev2/getHospBasisList` 있음 | 실측(경로) · Swagger |
| 약국 | `B551182/pharmacyInfoService/getParmacyBasisList` 있음. **`Parmacy` 오타가 스펙입니다.** `getPharmacyBasisList` 는 `NO_OPENAPI_SERVICE_ERROR` | 실측(경로) |
| 활용가이드의 낡은 주소 | 병원 가이드(2021-06)의 예시 `hospInfoService1/getHospBasisList1` 은 **폐기**되어 `NO_OPENAPI_SERVICE_ERROR` 입니다. 가이드 예시를 그대로 옮기면 안 됩니다 | 실측(경로) |
| 요청 변수 | `yadmNm`(UTF-8), `xPos`·`yPos`(WGS84), `radius`(m), `sidoCd`·`sgguCd`·`emdongNm`·`clCd`·`dgsbjtCd`·`zipCd`. 약국은 `clCd`·`dgsbjtCd`·`zipCd` 없음 | Swagger · 가이드 |
| 응답 | `yadmNm`·`addr`·`telno`·`clCdNm`·`distance`·좌표. 좌표 필드 이름이 병원 `XPos`·`YPos`, 약국 `xPos`·`yPos` 로 대소문자가 다릅니다(가이드 기준) | 가이드 |
| JSON | 공식 포맷은 XML 이고 Swagger 의 `produces` 도 XML 뿐이지만, `_type=json` 이면 JSON 이 옵니다 | 공식 · 실호출(9-5) |
| 한도·조건 | 개발 10,000건, 공공누리 1유형 | 공식 |

### 9-4. 그 밖의 경로

| 대상 | 결과 | 근거 |
|---|---|---|
| 서울교통공사 편의시설 15143841 | `B553766/facility/getFcElvtr` 있음(에스컬레이터 `getFcEsctr` 등 14개 연산). 결과 형식은 `_type` 이 아니라 **`dataType=JSON`**. 요청 `lineNm`·`stnCd`·`stnNm`·`vcntEntrcNo`·`oprtngSitu`, 응답에 가동현황 `oprtngSitu`·근접 출입구 `vcntEntrcNo`·층 정보. 이용허락범위 제한 없음 | 실측(경로) · Swagger |
| 서울교통공사 빠른하차 15143840 | `B553766/inout/getFstExit` 있음. `dataType=JSON`, `lineNm`·`stnCd`·`stnNm` | 실측(경로) · Swagger |
| 경기 GBIS v2 | `6410000/busarrivalservice/v2/getBusArrivalListv2`·`getBusArrivalItemv2`, `busstationservice/v2/getBusStationAroundListv2` 등 있음. 도착 응답에 분 단위 `predictTime1/2` 와 **초 단위 `predictTimeSec1/2`**, 저상 `lowPlate1/2` | 실측(경로) · Swagger |
| TAGO 도시코드 | `1613000/BusSttnInfoInqireService/getCtyCodeList` 있음. 인천 값은 키가 있어야 봅니다 | 실측(경로) |
| 카카오 대중교통 | `GET https://dapi.kakao.com/v2/routing/publictraffic`, 헤더 `Authorization: KakaoAK {REST 키}`, `start_x`·`start_y`·`end_x`·`end_y` | 공식(개발자 문서) |

`PublicDataInterceptor` 는 모든 게이트웨이 요청에 `_type=json` 을 붙이는데, 서울교통공사는
`dataType`, GBIS 는 `format` 을 씁니다. 이 둘을 붙일 때는 인터셉터가 붙이는 값과 별개로 각 API 의
형식 변수를 따로 넘겨야 합니다.

### 9-5. 실호출 결과 (2026-10-14)

`tools/probe_metro_apis.py` 로 불렀습니다. 카카오·Google 은 키가 없어 건너뛰었습니다.

**서울 버스 4종 — 이어 부르기가 됩니다.** 서울시청 좌표에서 시작해 한 번씩 불렀습니다.

```
getStationByPos(300m)  → 20건. 시청역 stationId=101900011 arsId=02503
getRouteByStation      → 2건.  종로09 busRouteId=100900003
getStaionByRoute       → 19건. 시청역의 seq=9
getArrInfoByRoute(ord=9) → vehId1·exps1=24(초)·busType1=0·arrmsg1="곧 도착"
getBusPosByRtid        → 4대, busType 0
```

- `resultType=json` 이면 네 개 모두 `msgHeader.headerCd`·`msgBody.itemList` 모양의 JSON 입니다.
  `resultType` 을 빼면 XML 입니다.
- §8-2 대로 `getStationByPos` 가 `stationId`·`arsId` 를 둘 다 주고, §8-3 의 `ord` 는
  `getStaionByRoute` 의 `seq` 로 채우면 됩니다.

**서울 API 는 경계 밖 경기 정류소도 줍니다.** `getStationByPos(500m)` 결과입니다.
광명시청 19건(`광명시청.한국폴리텍대학` 등), 수원시청 6건(`수원시청역8번출구.씨네파크.헌혈의집` 등).
그 정류소의 노선이 서울 노선만인지, 경기 노선까지인지는 아직 보지 않았습니다.

**환승경로 15000414 — 서울 밖까지 경로가 나옵니다.**

| 출발 → 도착 | 경로 수 | 구성 | 첫 경로 |
|---|---|---|---|
| 서울시청 → 강남역 | 20 | 모두 버스+버스 | 39분. 5005(명동입구→순천향대학병원) / 420(→신분당선강남역) |
| 서울시청 → 수원시청 | 20 | 버스+버스 6, 버스+지하철+버스 12, 지하철+버스 1, 지하철+지하철+버스 1 | 53분. 8800(광교→수원월드컵경기장) / 3002(→수원시청역8번출구) |

- 경기 정류소 ID 가 GBIS 정류소 ID 체계(`202000041` 등 수원 `2020…`)로 옵니다.
- 지하철 구간은 `routeId` 가 `null`, `fid`·`tid` 가 역 코드(`02010` 시청역)이고 `railLinkList` 가 붙습니다.
- **`distance` 를 믿으면 안 됩니다.** 서울시청 → 수원시청 버스+버스 경로가 8,397m 로 왔습니다
  (직선거리만 약 34km). 지하철이 들어간 경로는 31,993m 로 그럴듯합니다. 뜻은 미확인입니다.
- 서울시청 → 강남역 상위 20개에 지하철 경로가 하나도 없습니다. 정렬 기준은 미확인입니다.

**심평원 병원·약국 — 응답 모양이 TAGO 와 같습니다.**

| 요청 | totalCount | `items` 모양 |
|---|---|---|
| 병원 `yadmNm=성모`, 서울시청 3km | 10 | `item` 배열 |
| 병원 `yadmNm=서울의료원`, 가이드 좌표 500m | 1 | `item` 객체 하나 |
| 병원 `yadmNm=노원 피부과` | 0 | `items: ""` |
| 약국 `yadmNm=온누리`, 서울시청 1km | 5 | `item` 배열 |

- §2-1 의 세 모양 그대로라 `TagoItemsAdapterFactory` 를 그대로 쓸 수 있습니다.
- **반경 검색 결과가 거리순이 아닙니다.** 성모 3km 는 2,745m → 896m → 455m 순으로 왔습니다.
  가까운 순 정렬은 앱이 해야 합니다.
- `distance` 는 소수점 30자리가 넘는 문자열입니다(`"455.35843462367368341203397653469839487"`).
  `Double` 로 읽으면 됩니다.

**TAGO 도시코드.** 인천 `23`. 경기는 수원 `31010`, 성남 `31020`, 안양 `31040`, 부천 `31050`,
고양 `31100`, 용인 `31190`. `getCtyCodeList` 는 `totalCount` 를 주지 않습니다(전체 138개).

**경기 TAGO 는 GBIS 보다 늦습니다.** 수원시청.수원일자리센터(TAGO `GGB202000003` = GBIS `202000003`,
즉 TAGO 경기 정류소 ID 는 `GGB` + GBIS ID)에서 같은 노선의 첫 차를 60초 간격 5회 비교했습니다.

```
TAGO − GBIS: 중앙값 +119초, +1정거장   범위 +8 ~ +240초   (23쌍)
```

- TAGO 가 항상 **더 멀리** 있다고 봅니다. 같은 차량이 TAGO 에서 0~3정거장 더 뒤에 있습니다(첫 시험 포함).
  TAGO 의 위치가 늦게 갱신된다는 뜻으로 읽습니다.
- 30초 간격 시험에서는 TAGO 값이 두 회차 연속 똑같았습니다. 갱신 주기가 30초보다 깁니다.
- 한쪽에만 있는 차량(곧 도착하는 차를 TAGO 만 보여 줌)을 짝지으면 −1988초 같은 값이 나오므로
  스크립트는 정거장 차이가 5를 넘으면 따로 셉니다.
- 이번에 비교한 것은 **도착정보**입니다. 하차 알림이 쓰는 TAGO **버스위치정보**(§4)도 같은 만큼 늦는지는
  보지 않았습니다. 같다면 경기에서 하차 알림이 한 정거장 늦을 수 있습니다.
