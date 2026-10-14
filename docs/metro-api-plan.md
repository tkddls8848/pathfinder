# 수도권 무료 API 연동 계획

최종 갱신: 2026-10-13

이 앱을 수도권(서울·경기·인천)에서 쓸 수 있게 하려고 무료 API를 조사하고 연동 순서를 정한
문서입니다. **구현 전 계획입니다.** 지금 쓰는 API의 명세와 실응답은
[`public-data-apis.md`](public-data-apis.md), 출시까지 남은 일은 [`roadmap.md`](roadmap.md)에
있습니다. 연동을 마친 항목은 이 문서에서 지우고 그 두 문서로 옮깁니다.

**진행 상황 (2026-10-14).** 0단계를 마쳤습니다. 카카오·Google 실호출(키 없음)과 상업 이용 문의만
남았습니다. 결과는 [`public-data-apis.md`](public-data-apis.md) §9 에 있고 아래 표의 근거 등급도 올렸습니다.
1단계는 막힌 것이 없습니다. 0단계에서 새로 생긴 판단 하나(경기 GBIS)를 5절에 더했습니다.

---

## 0. 결론

| 하려는 일 | 쓸 API | 무료 범위 | 단계 |
|---|---|---|---|
| 병원·약국 목적지 검색 | 건강보험심사평가원 병원정보·약국정보 (data.go.kr 15001698·15001673) | 개발계정 일 10,000건 | 1 |
| 주소 검색 | 도로명주소 API (코드는 있고 키만 없음) | 무료 | 1 |
| 그 밖의 장소 (마트·주민센터 등) | 카카오 로컬 키워드 검색 | API별 일 100,000건 | 1 (약관 판단 후) |
| 서울 버스 | 서울특별시 버스 4종 (15000303·15000193·15000314·15000332) | 데이터셋별 일 1,000건 | 2 |
| 경기·인천 버스 | 지금 쓰는 TAGO 그대로 | API별 일 10,000건 | — |
| 지하철을 넣은 경로 | 서울시 대중교통환승경로 조회 (15000414) 또는 카카오맵 대중교통 경로 | 일 1,000건 | 3 (판단 필요) |
| 승강기·빠른하차 안내 | 서울교통공사 편의시설위치·빠른하차 (15143841·15143840) | 일 10,000건 | 4 |

조사에서 나온 사실 두 가지가 계획의 바탕입니다.

1. **수도권에서 TAGO 에 빠진 곳은 서울뿐입니다.** TAGO 연계 현황을 보면 경기 시군(고양·수원·용인 등)과
   인천은 실시간으로 연계되어 있고 서울은 정적 정보만 있습니다(공식). 앱 실측도 같습니다
   (수원시청 15건, 서울시청 0건). 그래서 서울 버스만 붙이면 수도권 버스가 갖춰집니다.
2. **공공데이터포털에도 환승 경로 API 가 있습니다.** 서울시 대중교통환승경로 조회(15000414)입니다.
   주소·요청·응답을 공식 활용가이드로 확인해 [`public-data-apis.md`](public-data-apis.md) §3 을 정정했습니다
   (§9-2). 실호출에서 서울 → 수원 경로에 경기 버스와 지하철이 섞여 나왔습니다(§9-5).

쓰지 않기로 한 것과 그 이유는 2-2·2-3 표의 「판단」 열에 있습니다.

---

## 1. 전제

- **앱은 기기에서 공공 API 를 직접 부릅니다.** 개발에 쓰는 MCP(chronicle `dataportal_search`)에는
  연결하지 않습니다. 어르신이 쓰는 앱이 개발 도구에 기대면 안 됩니다.
- **근거 등급.** 처음 조사한 환경에서는 data.go.kr·카카오·서울시 등 공식 사이트에 직접 접속하지
  못했습니다. 2026-10-13 0단계에서 공식 페이지·Swagger·활용가이드를 직접 받아 다시 확인했습니다.
  그래서 수치마다 근거를 붙였습니다.
  - **실측(경로)**: 키 없이 불러 경로가 있는지만 확인함([`public-data-apis.md`](public-data-apis.md) §9)
  - **공식**: 공식 페이지·Swagger·활용가이드 문구. 직접 열람한 것은 「공식(열람)」
  - **2차**: 개발자 포럼 운영 답변, GitHub 의 실측 코드
  - **미확인**: 근거를 찾지 못함

  어느 등급이든 **0단계 실호출로 다시 확인한 뒤에** 코드에 넣습니다. 추측한 URL 을 넣으면 조용히 404 가
  난다는 교훈([`public-data-apis.md`](public-data-apis.md) §2-6)을 그대로 따릅니다.

---

## 2. 조사 결과

### 2-1. 수도권 버스

| 지역 | TAGO | 자체 API | 판단 |
|---|---|---|---|
| 서울 | 정적 정보만 연계 (공식) | 서울 버스 4종, `http://ws.bus.go.kr/api/rest/`, data.go.kr 키. https 안 됨 (실측) | 자체 API 필요 |
| 경기 | 실시간 연계 (공식) | GBIS v2 4종 (15080666·15080662·15080346·15080648), `https://apis.data.go.kr/6410000/…/v2`, `format=json` (공식). 경로 있음 (실측(경로)) | TAGO 유지. GBIS 는 품질 개선 후보 |
| 인천 | 실시간 연계 (공식). `cityCode=23` (실호출) | 인천 버스 (15059084 등, 기관코드 6280000), XML (공식) | TAGO 유지 |

경기에서 TAGO 도착시각이 GBIS 보다 1~3분 늦었다는 실측(2차)을 0단계에서 다시 쟀습니다. 수원시청 앞 정류소
5개 노선, 60초 간격 5회에서 **TAGO 가 중앙값 119초·1정거장 늦었습니다**(범위 +8~+240초). 같은 차량을
TAGO 는 0~3정거장 뒤에 있다고 봅니다([`public-data-apis.md`](public-data-apis.md) §9-5).
하차 알림이 쓰는 버스위치정보도 같은지는 아직 보지 않았습니다(5절).

**서울 버스 4종.**

| 데이터셋 | 경로 | 일 한도 | 승인 | 비고 |
|---|---|---|---|---|
| 15000303 정류소정보 | `stationinfo` | 1,000 (공식) | 자동 (공식) | 공공누리 1유형 (공식) |
| 15000193 노선정보 | `busRouteInfo` | 1,000 (공식) | 자동 (공식) | 공공누리 1유형 (공식) |
| 15000314 도착정보 | `arrive` | 1,000 (공식) | 미확인 | 공공누리 1유형 (공식(열람)) |
| 15000332 위치정보 | `buspos` | 1,000 (공식) | 자동 (공식) | "제3자 권리 포함" 표기 (공식) |

- **JSON 이 됩니다.** api.bus.go.kr 레퍼런스에는 XML 만 있지만 `resultType=json` 이면 네 데이터셋 모두
  JSON 이 옵니다(실호출). §8-1 의 가장 큰 변경(XML 컨버터)이 사라졌습니다. 변수 이름이 TAGO 의 `_type` 과 다릅니다.
- **경계 밖 정류소도 줍니다.** `getStationByPos` 가 광명시청 19건, 수원시청 6건을 줍니다(실호출).
- **https 는 안 됩니다.** 443 포트에 연결이 안 되고(실측), 활용가이드도 전송 암호화를 "없음"으로
  적습니다(공식(열람)). 4-1 의 평문 예외가 필요합니다.
- **이용 조건이 엇갈립니다.** data.go.kr 은 공공누리 1유형(상업적 이용 가능)인데, api.bus.go.kr
  소개 페이지 원문은 "당해 오픈 API 서비스는 상업적으로 이용 불가합니다"입니다(공식(열람)).
  어느 쪽이 우선하는지는 문의해야 알 수 있습니다(5절).

**저상버스 표기는 공급자마다 다릅니다.**

| 공급자 | 필드 | 값 |
|---|---|---|
| TAGO | `vehicletp` 문자열 | 지금 `BusArrival.isLowFloor` 가 "저상" 포함 여부로 판정 |
| 서울 | `busType1/2`(도착), `busType`(위치) | 0 일반, 1 저상 (공식). 2 굴절 (2차) |
| 경기 GBIS | `lowPlate1/2`, `lowPlate` | 0 일반, 1 저상, 2 2층, 5 전세, 6 예약, 7 트롤리 (공식) |
| 인천 | `LOW_TP_CD` | 값 미확인 (2차) |

### 2-2. 경로 탐색

| 후보 | 무료 | 지하철 | 키 보호 | 약관 | 판단 |
|---|---|---|---|---|---|
| 지금 `RoutePlanner` (정류소·노선 조합) | — | 없음 (버스만) | — | — | 유지. 버스 경로의 기준 |
| 서울시 대중교통환승경로 조회 15000414 (`getPathInfoByBusNSub`) | 개발 1,000건, 개발·운영 자동승인 (공식(열람)) | 있음 | 불가 (data.go.kr 키) | 공공누리 1유형 (공식). api.bus.go.kr 의 상업 이용 불가 문구도 걸림 | **1순위 후보.** 주소 `ws.bus.go.kr/api/rest/pathinfo`(http), WGS84, JSON (실호출). 서울 → 수원 경로에 경기 버스·지하철이 섞여 나옴. `distance` 는 믿을 수 없음. 인천은 미확인 |
| 카카오맵 대중교통 경로 `dapi.kakao.com/v2/routing/publictraffic` (2026-07 출시). `start_x`·`start_y`·`end_x`·`end_y` (공식(열람)) | 일 1,000건, 계정에서 처음 켠 앱 하나만 (공식 발췌) | 있음 | 불가 (REST 키, 제한은 호출 IP 뿐) | 응답 저장 금지, 실시간 표시만 (2차). 첫 승차 전·마지막 하차 후 도보 상세 없음 | 2순위 후보. 한도가 남았는데 한도 초과 오류가 났다는 보고가 여럿 (2차) |
| ODsay LAB | 일 30·200·1,000건으로 공식 페이지끼리 다름 | 있음 | 미확인 | 비상업 목적만, 광고와 함께 두면 안 됨, "powered by www.ODsay.com" 표기 의무 | 쓰지 않음 |
| TMAP 대중교통 | 일 10건 (공식) | 있음 | 미확인 | 받은 데이터 24시간 넘게 사용 금지 | 쓰지 않음 |
| Google Routes API (TRANSIT) | 월 10,000건 (공식) | 미확인 | **가능** (Android 패키지·서명 제한) | 지도 없이 쓰면 Google 로고·출처 표시 | 한국 대중교통 경로가 나오는지 미확인. 0단계 시험만 |
| KTDB 전국 GTFS | 무료 (자료 신청) | 있음 | 키 없음 | 승인 없이 배포·상업 이용 금지 | 쓰지 않음. 연 1회 평일 하루분, 약 1.5년 지연, 실시간 없음 |
| 네이버 Directions · 카카오모빌리티 길찾기 | — | — | — | — | 자동차 전용이라 해당 없음 |

**외부 경로 API 를 써도 진행 방향 검증은 그대로 둡니다.** 외부 결과의 승차·하차 정류소를 노선
경유 순번으로 다시 확인합니다([`public-data-apis.md`](public-data-apis.md) §3 의 4번 단계).
반대 방향 정류장 안내는 이 앱에서 가장 치명적인 실패라, 남이 준 경로라도 그대로 믿지 않습니다.

### 2-3. 목적지 검색

| 후보 | 무료 | 키 | 약관 | 판단 |
|---|---|---|---|---|
| 심평원 병원정보 15001698 `B551182/hospInfoServicev2/getHospBasisList` | 개발 10,000건, 자동승인 (공식) | data.go.kr 키 | 공공누리 1유형, 저장 가능 | **병원 검색 1순위.** 이름 부분일치 + 반경 검색 동작, `items` 세 모양이 TAGO 와 같음 (실호출). **결과가 거리순이 아님** |
| 심평원 약국정보 15001673 `B551182/pharmacyInfoService/getParmacyBasisList` | 개발 10,000건 (공식) | 같음 | 공공누리 1유형 (공식(열람)) | 약국 검색. 이름 + 반경 검색 동작 (실호출). `Parmacy` 오타가 스펙 |
| 국립중앙의료원 (지금 쓰는 것) | — | — | — | 2026-08 에 0건이던 한글 필터가 **지금은 동작**(`Q0`·`Q1`·`QN` 부분일치, 실호출). 앱은 1회 인코딩이라 앱 버그가 아니었음([`public-data-apis.md`](public-data-apis.md) §2-7). 반경 검색이 없어 `RegionResolver` 에 기대므로 심평원으로 옮김 |
| 도로명주소 검색 + 좌표제공 | 무료, 일 한도 미확인 | 개발키 자동승인 (공식). 좌표제공은 별도 키 (2차) | 저장 가능 (2차) | 키 신청. 코드는 이미 있음 |
| 카카오 로컬 (키워드·카테고리·주소, `coord2regioncode`) | API별 일 100,000건, 앱 월 3,000,000건, 계정 첫 앱만 (공식 발췌). 유료 설정을 켜지 않으면 넘어도 429 로 멈춤 | REST 키만, APK 에서 보호 불가 | 결과 저장·캐시 금지, 장소 ID·URL 만 저장 (2차) | 선택. 4-3 의 즐겨찾기 저장과 충돌 |
| V-World 검색·Geocoder | Geocoder 일 40,000건 (공식) | 발급 도메인에 묶임, 앱 사용 방법 미확인 | 저장 금지 (공식) | 보류 |
| 네이버 지역 검색 | 신규 키는 API HUB(종량제)에서만 발급 (공식) | Client ID·Secret | 저장 금지·출처 필수 (2차) | 쓰지 않음. 한 번에 5건 |
| NCP Geocoding | 무료량 2025-07-01 종료 (공식) | — | 저장 금지 (공식) | 쓰지 않음 |
| Nominatim (OSM) | 앱 전체 합쳐 초당 1건, 자동완성 금지 (공식) | — | — | 쓰지 않음 |

**심평원 검색에는 지역명을 이름에 섞지 않습니다.** 서울 + `yadmNm=피부과` 는 623건인데
`yadmNm=노원 피부과` 는 0건이었습니다(2차). 기관명에 없는 글자가 들어가면 0건이 됩니다.
지역은 좌표 반경(`xPos`·`yPos`·`radius`)으로 거르고 이름 칸에는 기관명만 넣습니다.
반경 검색을 쓰면 시군구 코드 변환도, `RegionResolver`(안드로이드 Geocoder) 의존도 필요 없습니다.

### 2-4. 지하철·교통약자 시설

| 데이터 | 주소 | 키 | 한도 | 판단 |
|---|---|---|---|---|
| 서울교통공사 편의시설위치 15143841 (`getFcElvtr` 외, 가동상태 `oprtngSitu`·근접 출입구 `vcntEntrcNo` 는 Swagger 에 있음) | `apis.data.go.kr/B553766/facility` (https), 형식 변수는 `dataType=JSON` (공식(열람)) | data.go.kr 키 | 10,000 (공식(열람)) | 4단계 1순위. 경로 있음 (실측(경로)) |
| 서울교통공사 빠른하차 15143840 | `apis.data.go.kr/B553766/inout/getFstExit` (실측(경로)), `dataType=JSON` | data.go.kr 키 | 10,000, 자동 (공식) | 4단계 |
| 서울 지하철역 엘리베이터 위치 (15098158 → 열린데이터광장 OA-21212) | 열린데이터광장 | 서울 일반 키 | 미확인 | 15143841 로 충분하면 쓰지 않음 |
| 승강기 가동현황 (OA-15994) | `openapi.seoul.go.kr:8088` (http) | 서울 일반 키 | 미확인 | 15143841 의 가동상태로 충분하면 쓰지 않음 |
| 지하철 실시간 도착 (OA-12764) | `swopenapi.seoul.go.kr` (http) | **실시간 지하철 키 별도 신청** | 일 1,000건 (공식). 활용사례 승인 시 해제되는데 GitHub·localhost 링크는 반려된 사례 (2차) | 3단계에서 지하철을 넣을 때만 |

---

## 3. 단계

### 0단계 — 실호출로 확인 (코드 변경 없음)

사람이 키를 넣고 한 번씩 부릅니다. 결과는 [`public-data-apis.md`](public-data-apis.md)에 적습니다.
키가 필요한 확인은 `tools/probe_metro_apis.py` 하나로 묶었습니다. 키는 환경 변수나 `local.properties` 의
`PUBLIC_DATA_SERVICE_KEY`(필수), `KAKAO_REST_API_KEY`·`GOOGLE_MAPS_API_KEY`(없으면 그 확인만 건너뜀)에서
읽고, 화면과 원본 응답 파일(`build/metro-probe/`)에서 키를 지웁니다. 아래 표의 「스크립트」 열이
`--only` 에 넣을 이름입니다. data.go.kr 활용신청은 데이터셋마다 따로 해야 합니다(서울 4종·15000414·
심평원 2종·GBIS 도착·TAGO 4종).
`apis.data.go.kr` 게이트웨이 API(심평원·GBIS·TAGO·서울교통공사)는 개발 PC 에서 chronicle MCP 의
`call_public_api` 로도 부를 수 있습니다. `ws.bus.go.kr`·`swopenapi.seoul.go.kr` 은 그 조회기가
"기관 키 필요"로 거절하므로 스크립트나 직접 호출로 부릅니다.

| 확인할 것 | 상태 (2026-10-13) | 스크립트 | 결과에 따라 |
|---|---|---|---|
| `ws.bus.go.kr` 이 https 를 받는가 | **끝. 안 됨** (443 연결 불가, 가이드도 암호화 없음) | `seoul-https` | 4-1 의 평문 예외가 필요함 |
| `resultType=json` 이 되는가 | **끝. 됨** (4종 모두) | `seoul-json` | 되면 XML 컨버터 불필요. §8-1 정정(가이드 기준으로 먼저 고침) |
| 15000414 의 호출 주소·응답·범위 | **끝.** 서울 안·서울 → 수원 모두 20개 경로. 경기 버스·지하철 포함 | `pathinfo` | 3단계 후보 결정. §3 정정(끝) |
| 카카오 대중교통 경로 | 요청 형식 확인. **실호출 남음 (카카오 REST 키 필요)** | `kakao-transit` | 3단계 후보 결정 |
| Google Routes TRANSIT 이 한국에서 나오는가 | **실호출 남음 (Google 키 필요)** | `google-transit` | 나오면 2-2 표에 올림 |
| 심평원 `yadmNm` + 반경 검색 | **끝. 됨.** 0·1·N건 모양이 TAGO 와 같음, 거리순 아님 | `hira` | 1단계 진행 |
| 국립중앙의료원 0건의 원인 | **끝.** 지금은 서울·강원 모두 동작. 서버 쪽 변화로 봄 | `nmc-encoding` | 심평원이 되면 따지지 않아도 됨 |
| 인천 `cityCode` | **끝. 23** | `tago-city` | 지역 분기 기준 |
| 경기 TAGO 와 GBIS 의 도착시각 | **끝.** TAGO 가 중앙값 119초·1정거장 늦음 | `gbis-vs-tago` | 5절 「경기 GBIS」 판단으로 넘김 |
| 서울 API 가 경기 쪽 정류소를 주는가 | **끝. 줌** (광명시청 19건, 수원시청 6건) | `seoul-outside` | 2단계 경계 처리 범위 |
| api.bus.go.kr "상업적 이용 불가" | 원문 확인(§9-1). 우선순위는 문의 남음 | — | 5절 판단 |

`gbis-vs-tago` 는 TAGO 의 경기 정류소 ID 가 `GGB` + GBIS 정류소 ID 라는 가정으로 같은 정류소를 찾습니다.
수원시청 앞(`GGB202000003` ↔ `202000003`)에서 노선 5개가 모두 짝지어져 가정이 맞았습니다.

### 1단계 — 목적지 검색 복구

[`roadmap.md`](roadmap.md) 「목적지를 이름으로 찾을 수 없다」를 풉니다. 가장 값싸고 사용자 체감이 큽니다.

| 할 일 | 바뀌는 곳 |
|---|---|
| 도로명주소 개발키 신청 (검색·좌표제공 각각) | `local.properties` 만. 코드는 이미 있음 |
| 병원 검색을 심평원 반경 + 이름 검색으로 교체 | `NetworkModule.kt`(기관별 베이스 상수 추가), `OtherApis.kt`, `OtherDtos.kt`, `PlaceRepository.searchHospitals` |
| 약국 검색 추가 (`PlaceKind.PHARMACY` 는 이미 있음) | 위와 같음 |
| 국립중앙의료원 API 는 심평원이 동작하면 뺌 | `HospitalApi` 와 그 DTO |

- 완료 기준: 에뮬레이터에서 "성모"를 검색하면 반경 안 병원이 가까운 순으로 나온다.
  **API 가 거리순으로 주지 않으므로** 정렬은 앱이 `distance` 로 합니다(0단계 실호출).
- 테스트: 심평원 응답의 0·1·N건 모양(TAGO 와 같은 `items` 세 모양임을 0단계에서 확인해
  `TagoItemsAdapterFactory` 를 그대로 씀), 0건이면 `NoResult`, 거리 정렬.
- 좌표 필드가 병원 `XPos`·`YPos`, 약국 `xPos`·`yPos` 로 대소문자가 다릅니다(가이드). DTO 를 따로 둡니다.
- 카카오 로컬은 4-3 판단 뒤에 붙입니다.

### 2단계 — 서울 버스

[`roadmap.md`](roadmap.md) 「서울에서 동작하지 않는다」를 풉니다. [`public-data-apis.md`](public-data-apis.md)
§8 의 다섯 차이에 아래를 더합니다.

| 할 일 | 바뀌는 곳 |
|---|---|
| 교통 공급자 인터페이스를 두고 TAGO·서울을 구현으로 나눔. `RoutePlanner` 는 인터페이스만 안다 | `TransitRepository.kt` 를 `TagoTransitSource`·`SeoulTransitSource` 로 분리 |
| 정류소·노선 식별자를 공급자별로 (`cityCode: Int` 하나로 서울의 `arsId`·`stId` 를 담을 수 없음) | `Transit.kt` 의 `BusStop`·`BusRoute`, 이를 쓰는 곳 |
| 출발지 좌표로 공급자 선택. 서울이면 서울, 아니면 TAGO. 판정이 안 되면 TAGO → 서울 순으로 시도 | `RegionResolver` 또는 새 분기 함수 |
| 서울 오류 코드(`headerCd` 숫자, `4` 는 빈 결과)와 키 오류(HTTP 401, 봉투 없음)를 `FailureReason` 으로 | 서울 응답 봉투 |
| 도착정보 평탄화(`vehId1/2`, `exps1/2`)와 저상 `busType` 판정 | 서울 DTO, `BusArrival` |
| `ws.bus.go.kr` 한 도메인만 평문 http 예외 (https 안 됨 확인) | `res/xml/network_security_config.xml`, 매니페스트 |
| 활용신청 4건, 운영계정 증량 신청 | 포털 (코드 아님) |

- **경계를 넘는 여정은 첫 범위에서 뺍니다.** 서울과 TAGO 는 노선 ID 체계가 달라 직통·환승 교집합이
  안 됩니다. 출발·도착이 같은 공급자일 때만 경로를 만들고, 아니면 지금처럼 `NoRoute` 로 갈라 보냅니다.
- **서울 API 가 경계 밖 정류소도 줍니다**(광명·수원, 0단계). 그래서 "서울 API 가 0건이면 서울 밖"으로
  판정할 수 없습니다. 공급자 선택은 좌표의 행정구역으로 합니다.
- **한도가 빠듯합니다(추정).** 여정 1회에 도착정보 약 50회(20분 대기, §5), 차량위치 약 100회(15초 간격,
  25분 승차)를 씁니다. 앱 전체 사용자가 하루 여정 10회 정도면 차량위치 1,000건에 닿습니다.
  운영계정 증량 신청을 2단계 시작과 함께 겁니다.
- 완료 기준: 서울 좌표 두 곳으로 경로 → 도착 안내 → 하차 알림까지 에뮬레이터에서 실측.
- 테스트: 서울 응답 0·1·N건 파싱, `headerCd` 분류, `busType` 저상 판정, 서울 공급자로 진행 방향 검증
  (`RoutePlannerTest` 의 반대 방향 사례를 서울판으로).

### 3단계 — 지하철을 경로에 넣기 (판단 필요)

지금 경로는 버스뿐입니다. 서울에서는 지하철을 빼면 쓸 만한 경로가 크게 줄어듭니다.

- 공급자: 0단계 결과로 15000414 와 카카오 대중교통 중 고릅니다. 기준은 수도권 범위, 저장 금지 여부(4-3),
  한도, 도보 구간 제공입니다.
- `Journey` 에 지하철 구간(`JourneyLeg`)을 더하고 안내 화면에 지하철 단계를 만듭니다.
- **풀어야 할 문제: 지하에서는 GPS 가 안 잡힙니다.** 버스의 하차 알림은 차량 순번
  ([`public-data-apis.md`](public-data-apis.md) §4)이라 위치가 없어도 되지만, 지하철에는 같은 근거가
  없습니다. 지하철 실시간 도착(열차 위치)으로 남은 역을 세는 방법을 검토합니다. 별도 키·http·
  일 1,000건이 따라옵니다.

### 4단계 — 승강기·빠른하차 안내

목업의 승강기 화면(🛗)을 살립니다. 3단계가 있어야 쓸 곳이 생깁니다.

- 15143841 로 지하철 구간의 승·하차역 엘리베이터 위치와 가동상태를 보여 줍니다. 고장이면 다른 출구를 안내합니다.
- 15143840 으로 엘리베이터와 가까운 칸·문 번호를 안내합니다.
- 둘 다 게이트웨이(https)라 지금 키와 `PublicDataInterceptor` 를 그대로 씁니다.

---

## 4. 공통 과제

### 4-1. 평문 http

서울 버스(`ws.bus.go.kr`), 서울 지하철 실시간(`swopenapi.seoul.go.kr`), 열린데이터광장
(`openapi.seoul.go.kr:8088`)은 공식 주소가 http 입니다. 서울 버스와 15000414 는 https 가 **안 된다는 것까지
확인했습니다**(실측). `targetSdk = 35` 에서는 평문 http 가 기본으로
막히고, 이 앱에는 `network_security_config` 가 없습니다. 허용하더라도 도메인 단위로만 열고, 그 경우
**인증키와 요청 좌표가 평문으로 나간다**는 점을 받아들여야 합니다.

### 4-2. 키 내장과 공유 한도

인증키는 `BuildConfig` 로 APK 에 들어갑니다. 앱 패키지·서명으로 키를 묶을 수 있는 곳은 조사한 것 중
Google 뿐입니다(공식). data.go.kr·카카오 키는 APK 에서 꺼낼 수 있고, 남이 쓰면 한도가 내 키에서 빠집니다.
또 모든 사용자가 키 하나의 일일 한도를 나눠 씁니다.

- 선택지 A: 내장을 유지하고 운영계정 증량을 받습니다. 시험 배포까지는 이것으로 충분합니다.
- 선택지 B: 키를 보관하고 캐시하는 얇은 중계 서버를 둡니다. 개발용 MCP 와는 무관한 별도 서버입니다.

### 4-3. 저장 금지 약관과 즐겨찾기

앱은 자주 가는 곳을 좌표와 함께 저장합니다(`PlaceRepository.savePlace`, Room). 다음 출처는 결과 저장을
막습니다.

| 출처 | 저장 |
|---|---|
| 심평원·도로명주소 | 가능 |
| 카카오 로컬 | 장소 ID·URL 만 (2차) |
| 카카오 대중교통 경로 | 응답 저장 금지 (2차) |
| V-World | 저장 금지 (공식) |

카카오 로컬을 쓰면 즐겨찾기에는 장소 ID 만 저장하고 쓸 때 다시 조회하거나, 카카오 결과는 즐겨찾기
대상에서 빼야 합니다. 카카오 대중교통을 쓰면 안내 중인 경로를 메모리에만 두고 디스크에 남기지 않습니다.

### 4-4. 출처 표시

공공누리 1유형은 출처 표시가 조건입니다. 카카오 대중교통은 미확인이고, ODsay 처럼 표기를 의무로 두는
곳도 있습니다. 설정 화면에 쓰는 데이터의 출처 목록을 한 곳에 둡니다.

---

## 5. 판단이 필요한 것

| 판단 | 선택지 | 시점 |
|---|---|---|
| 경기 GBIS | TAGO 유지 / 경기는 GBIS 로 (2단계의 공급자 인터페이스에 세 번째 구현). 근거: 경기 TAGO 도착정보가 중앙값 2분·1정거장 늦음. **먼저 TAGO 버스위치정보도 늦는지 잽니다** — 늦다면 경기 하차 알림이 한 정거장 늦을 수 있어 GBIS 쪽으로 기웁니다 | 2단계 설계 전 |
| 서울 버스 평문 http 허용 | 허용(`ws.bus.go.kr` 한 도메인) / 4-2 의 중계 서버 뒤로 숨김 / 서울 버스를 넣지 않음. https 는 안 됨이 확인되어 "기다림"은 선택지에서 빠짐 | 2단계 전 |
| 카카오 로컬 도입 | 도입(즐겨찾기는 ID 만) / 심평원·도로명주소만 | 1단계 |
| 지하철 경로 공급자 | 15000414 / 카카오 대중교통 / 넣지 않음 | 0단계 뒤, 3단계 전 |
| 키 보관 | APK 내장 + 증량 / 중계 서버 | 공개 배포 전 |
| 상업 이용 | api.bus.go.kr 원문은 "상업적으로 이용 불가", data.go.kr 은 공공누리 1유형. 서울시(api.bus.go.kr)에 문의해 어느 쪽이 우선인지 받아 둠 | 출시 전 |

---

## 6. 0단계 뒤에 고칠 기존 문서

- [`public-data-apis.md`](public-data-apis.md) §3 "환승 경로 API 가 없다" — 정정함 (끝)
- 같은 문서 §8-1 "XML 전용" — 정정함 (끝)
- 같은 문서 §2-7 병·의원 한글 필터 — 지금은 동작함으로 정정함 (끝)
- [`roadmap.md`](roadmap.md) 1절 — 단계가 끝날 때마다 해당 항목 삭제

---

## 출처

처음에는 2026-10-13 검색으로 확인했습니다(공식 사이트를 직접 열지 못해 검색엔진이 읽은 내용).
같은 날 0단계에서 data.go.kr 데이터셋 페이지·Swagger·활용가이드, api.bus.go.kr 소개 페이지,
카카오 개발자 문서를 직접 받아 다시 확인했습니다. 「공식(열람)」 표시가 그것입니다.

- 서울 버스: [15000303](https://www.data.go.kr/data/15000303/openapi.do) ·
  [15000332](https://www.data.go.kr/data/15000332/openapi.do) ·
  [환승경로 15000414](https://www.data.go.kr/data/15000414/openapi.do)
- 경기 GBIS: [15080346](https://www.data.go.kr/data/15080346/openapi.do) ·
  [GBIS 매뉴얼](https://www.gbis.go.kr/gbis2014/publicService.action?cmd=mBusArrivalStation)
- 인천: [15059084](https://www.data.go.kr/data/15059084/openapi.do)
- TAGO: [연계데이터 현황](https://www.tago.go.kr/v5/link/current_data.jsp)
- 서울교통공사: [15143840](https://www.data.go.kr/data/15143840/openapi.do) ·
  [15143841](https://www.data.go.kr/en/data/15143841/openapi.do)
- 열린데이터광장: [OA-12764](https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do) ·
  [OA-15994](https://data.seoul.go.kr/dataList/OA-15994/S/1/datasetView.do) ·
  [OA-21212](https://data.seoul.go.kr/dataList/OA-21212/S/1/datasetView.do)
- 심평원: [15001698](https://www.data.go.kr/data/15001698/openapi.do) ·
  [15001673](https://www.data.go.kr/en/data/15001673/openapi.do) ·
  [HIRA 명세](https://opendata.hira.or.kr/op/opc/selectOpnsApi.do?sno=713)
- 도로명주소·V-World: [15057017](https://www.data.go.kr/data/15057017/openapi.do) ·
  [V-World Geocoder 15101106](https://www.data.go.kr/data/15101106/openapi.do) ·
  [V-World 검색 2.0](https://www.vworld.kr/dev/v4dv_search2_s001.do)
- 카카오: [로컬·지도 무료 한도 공지](https://devtalk.kakao.com/t/api-notice-on-new-kakao-map-api-features-and-free-quota-policy/150222) ·
  [쿼터](https://developers.kakao.com/docs/latest/ko/getting-started/quota) ·
  [보안 가이드](https://developers.kakao.com/docs/latest/ko/getting-started/security-guideline) ·
  [카카오맵 REST API](https://developers.kakao.com/docs/ko/kakaomap/rest-api)
- ODsay: [운영정책](https://lab.odsay.com/doc/totalPolicy) · [FAQ](https://lab.odsay.com/community/faq)
- TMAP: [대중교통 경로](https://transit.tmapmobility.com/docs/routes) · [약관](https://tmapapi.tmapmobility.com/terms.html)
- Google: [가격](https://developers.google.com/maps/billing-and-pricing/pricing) ·
  [Routes 정책](https://developers.google.com/maps/documentation/routes/policies)
- 네이버: [API HUB](https://guide.ncloud-docs.com/docs/apihub-overview) ·
  [Directions 15](https://guide.ncloud-docs.com/docs/en/maps-direction15-api)
- KTDB GTFS: [공지](https://www.ktdb.go.kr/www/selectBbsNttView.do?key=45&bbsNo=2&nttNo=3764)
- OSM: [Nominatim 정책](https://operations.osmfoundation.org/policies/nominatim/)
- 2차 근거(GitHub 실측): [심평원 이름 검색](https://github.com/hamkegayo/hamkegayo-next/pull/237) ·
  [심평원 지역명 0건](https://github.com/Medical-Mate/Backend/issues/154) ·
  [국립중앙의료원 Q0·Q1 동작](https://github.com/csh0616/GangWon_Go/pull/25)
