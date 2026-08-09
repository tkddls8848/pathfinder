# 공공데이터 연계 명세

최종 갱신: 2026-08-08

이 앱이 쓰는 공공 데이터와, 왜 그 데이터를 그렇게 쓰는지를 정리한 문서입니다.

---

## 1. 사용 중인 API

| # | 데이터셋 | 제공기관 | 용도 | 상태 |
|---|---------|---------|------|------|
| 1 | [버스정류소정보](https://www.data.go.kr/data/15098534/openapi.do) | 국토교통부 (TAGO) | 주변 정류소 탐색, 경유 노선 조회 | ✅ 실응답 확인 |
| 2 | [버스도착정보](https://www.data.go.kr/data/15098530/openapi.do) | 국토교통부 (TAGO) | 특정 노선 실시간 도착 예정, 저상버스 여부 | ✅ 실응답 확인 |
| 3 | [버스노선정보](https://www.data.go.kr/data/15098529/openapi.do) | 국토교통부 (TAGO) | 노선별 경유 정류소·순번 (진행 방향 검증) | ✅ 실응답 확인 |
| 4 | [버스위치정보](https://www.data.go.kr/data/15098533/openapi.do) | 국토교통부 (TAGO) | 실시간 차량 위치 → 하차 알림 | ✅ 실응답 확인 |
| 5 | [전국 병·의원 찾기](https://www.data.go.kr/data/15000736/openapi.do) | 국립중앙의료원 | 목적지(병원) 검색 | ⚠️ 응답은 오지만 **한글 필터 무효** (2-7) |
| 6 | [단기예보 조회서비스](https://www.data.go.kr/data/15084084/openapi.do) | 기상청 | 출발 전 날씨 경고 | ✅ 실응답 확인 |
| 7 | [도로명주소 검색/좌표변환](https://business.juso.go.kr) | 행정안전부 | 일반 목적지 검색 | ❌ 승인키 미발급 |

2026-08-08 기준. 1·3·4번은 앱에서 경로 한 건을 끝까지 만드는 데까지 확인했습니다.

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

### 2-7. 병·의원 API 는 한글 필터를 받지 않는다

`HsptlAsembySearchService/getHsptlMdcncListInfoInqire` 는 값의 종류에 따라 갈립니다.

```
필터 없음        → 78,689건   영문 코드 QZ=B → 1,432건   QD=D001 → 23,675건
Q0=서울특별시    →      0건   Q1=중구 → 0건   QN=<정확한 기관명> → 0건
```

인코딩을 네 가지로 시험했습니다 — UTF-8, UTF-8 이중, EUC-KR, 인코딩 없음.
**전부 0건입니다.** 응답 데이터에 실제로 들어 있는 시도명을 그대로 넣어도 0이므로
값이 틀린 것이 아닙니다. 서버 쪽 동작으로 판단합니다.

따라서 이 API 로는 **이름·지역 검색을 할 수 없습니다.** 목적지 검색은
7번(도로명주소)에 의존해야 하고, 그 승인키가 없으면 검색 기능 전체가 비어 있습니다.

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

---

## 3. 경로 탐색을 직접 구현한 이유

**공공데이터포털에는 "출발지 → 목적지 환승 경로"를 주는 오픈API 가 없습니다.**
제공되는 것은 정류소·노선·도착·위치 네 가지 원본 데이터뿐입니다.
(대중교통 길찾기는 ODsay, 카카오 등 상용 API 영역입니다.)

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

### 8-1. XML 전용입니다

모든 오퍼레이션의 Output 이 `XML` 하나뿐입니다. TAGO 의 `_type=json`([§2-4](#2-4-_typejson-을-빼먹으면-xml-이-온다))
같은 스위치가 **없습니다.** 지금 `NetworkModule` 은 Moshi 컨버터만 물려 있으므로
XML 컨버터를 따로 붙이거나 파서를 직접 써야 합니다. 다섯 중 가장 큰 차이입니다.

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
