#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
수도권 API 연동 0단계(docs/metro-api-plan.md §3) 중 인증키가 있어야 하는 확인을 한 번에 돌린다.

계획은 "추측한 URL 을 코드에 넣지 않는다"(public-data-apis.md §2-6)를 지키려고
연동 전에 실호출로 확인하도록 정해 두었다. 키 없이 할 수 있는 것(경로가 있는지, https,
공식 명세)은 이미 확인해 public-data-apis.md §9 에 적었다. 남은 것은 키가 있어야 한다.

인증키는 환경 변수 → local.properties 순으로 찾는다. 없는 키를 쓰는 확인은 건너뛴다.

  PUBLIC_DATA_SERVICE_KEY   data.go.kr 키. 대부분의 확인 (데이터셋마다 활용신청 필요)
  KAKAO_REST_API_KEY        카카오 대중교통 경로. 없으면 건너뜀
  GOOGLE_MAPS_API_KEY       Google Routes TRANSIT. 없으면 건너뜀

사용 예:

  python3 tools/probe_metro_apis.py                  # 전부
  python3 tools/probe_metro_apis.py --list           # 확인 목록만
  python3 tools/probe_metro_apis.py --only hira,seoul-json
  python3 tools/probe_metro_apis.py --only gbis-vs-tago --rounds 5 --interval 30

출력은 화면의 요약과, --out 디렉터리(기본 build/metro-probe, 커밋되지 않음)의 원본 응답이다.
요청 URL 의 인증키는 화면에도 파일에도 남기지 않는다.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parent.parent

# 좌표는 (위도, 경도). 서울 API 는 x=경도, y=위도 로 받는다.
SEOUL_CITY_HALL = (37.5663, 126.9779)
GANGNAM_STATION = (37.4979, 127.0276)
SUWON_CITY_HALL = (37.2636, 127.0286)
GWANGMYEONG_CITY_HALL = (37.4786, 126.8646)   # 서울 경계 바로 밖
SEOUL_MEDICAL_CENTER = (37.6132113197367, 127.09854004628151)  # 심평원 활용가이드 예시 좌표

# (요약에 쓸 짧은 이름, 설명, 출발, 도착)
PAIRS = [
    ("서울 안", "시청 → 강남역", SEOUL_CITY_HALL, GANGNAM_STATION),
    ("서울→경기", "시청 → 수원시청", SEOUL_CITY_HALL, SUWON_CITY_HALL),
]

GW = "https://apis.data.go.kr/"
SEOUL_BUS = "http://ws.bus.go.kr/api/rest/"

SECRET = re.compile(r"((?:serviceKey|ServiceKey|key)=)[^&\s]*")


# ── 설정 ─────────────────────────────────────────────────────

def read_local_properties() -> dict[str, str]:
    path = ROOT / "local.properties"
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        values[k.strip()] = v.strip()
    return values


def load_keys() -> dict[str, str]:
    props = read_local_properties()
    keys = {}
    for name in ("PUBLIC_DATA_SERVICE_KEY", "KAKAO_REST_API_KEY", "GOOGLE_MAPS_API_KEY"):
        value = os.environ.get(name) or props.get(name, "")
        if value and not value.startswith("여기에"):
            keys[name] = value
    # ServiceKeyProvider 와 같은 정규화: Encoding 키를 넣어도 raw 로 되돌린다.
    if "PUBLIC_DATA_SERVICE_KEY" in keys and "%" in keys["PUBLIC_DATA_SERVICE_KEY"]:
        keys["PUBLIC_DATA_SERVICE_KEY"] = urllib.parse.unquote(keys["PUBLIC_DATA_SERVICE_KEY"])
    return keys


# ── HTTP ─────────────────────────────────────────────────────

@dataclass
class Reply:
    url: str          # 인증키를 지운 요청 URL
    status: int
    content_type: str
    text: str

    @property
    def is_json(self) -> bool:
        return self.text.lstrip().startswith(("{", "["))

    def json(self) -> Any:
        return json.loads(self.text)

    def xml(self) -> ET.Element:
        return ET.fromstring(self.text.encode("utf-8"))


class Http:
    def __init__(self, out: Path, secrets: list[str]):
        self.out = out
        self.seq = 0
        # 쿼리뿐 아니라 응답 본문에도 키가 되돌아올 수 있다(카카오 오류 메시지). 값 자체를 지운다.
        self.secrets = [v for v in secrets if len(v) >= 4]

    def scrub(self, text: str) -> str:
        for v in self.secrets:
            text = text.replace(v, "<redacted>").replace(urllib.parse.quote_plus(v), "<redacted>")
        return text

    def get(self, base: str, params: dict[str, Any], headers: dict[str, str] | None = None,
            label: str = "") -> Reply:
        # urlencode 는 값을 정확히 한 번만 퍼센트 인코딩한다. 앱의 Retrofit @Query 와 같다.
        query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        return self._send(urllib.request.Request(f"{base}?{query}", headers=headers or {}), label)

    def post_json(self, url: str, body: dict, headers: dict[str, str], label: str = "") -> Reply:
        data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST",
                                     headers={"Content-Type": "application/json", **headers})
        return self._send(req, label)

    def _send(self, req: urllib.request.Request, label: str) -> Reply:
        safe_url = SECRET.sub(r"\1<redacted>", req.full_url)
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                status, ctype, raw = resp.status, resp.headers.get("Content-Type", ""), resp.read()
        except urllib.error.HTTPError as e:
            status, ctype, raw = e.code, e.headers.get("Content-Type", ""), e.read()
        except (urllib.error.URLError, socket.timeout, TimeoutError) as e:
            status, ctype, raw = 0, "", f"연결 실패: {e}".encode("utf-8")
        text = self.scrub(raw.decode("utf-8", "replace"))
        self.seq += 1
        name = re.sub(r"[^\w.-]+", "_", label or "call")[:60]
        (self.out / f"{self.seq:02d}_{name}.txt").write_text(
            f"{req.get_method()} {safe_url}\n{status} {ctype}\n\n{text}", encoding="utf-8")
        return Reply(safe_url, status, ctype, text)


# ── 응답 모양 ────────────────────────────────────────────────

def gw_items(doc: dict) -> tuple[str, list[dict], Any]:
    """data.go.kr 게이트웨이 JSON 의 items 를 꺼낸다. public-data-apis.md §2-1 의 세 모양을 구분한다."""
    body = (doc.get("response") or {}).get("body") or {}
    items = body.get("items")
    total = body.get("totalCount")
    if items in (None, ""):
        return f'items="{items}" (0건 모양)' if items == "" else "items 없음", [], total
    item = items.get("item") if isinstance(items, dict) else items
    if isinstance(item, list):
        return f"item=배열 {len(item)}개", item, total
    if isinstance(item, dict):
        return "item=객체 하나 (1건 모양)", [item], total
    return f"item={type(item).__name__}", [], total


def gw_error(r: Reply) -> str | None:
    m = re.search(r"<errMsg>([^<]+)|\"errMsg\"\s*:\s*\"([^\"]+)\"", r.text)
    if m:
        return m.group(1) or m.group(2)
    if r.is_json:
        header = (r.json().get("response") or {}).get("header") or {}
        if header.get("resultCode") not in (None, "00", "0", "000"):
            return f"resultCode={header.get('resultCode')} {header.get('resultMsg')}"
    return None


def seoul_body(r: Reply) -> tuple[str, str, list[dict]]:
    """서울 버스(ws.bus.go.kr) 응답의 headerCd, headerMsg, itemList. JSON·XML 둘 다 읽는다."""
    if r.is_json:
        doc = r.json()
        if "msgHeader" not in doc and "error" in doc:   # 키 오류는 봉투 없이 HTTP 401 JSON 으로 온다
            return f"HTTP {r.status}", doc.get("message", ""), []
        header = doc.get("msgHeader") or {}
        items = (doc.get("msgBody") or {}).get("itemList") or []
        if isinstance(items, dict):
            items = [items]
        return str(header.get("headerCd")), str(header.get("headerMsg")), items
    root = r.xml()
    rows = [{c.tag: (c.text or "") for c in it} for it in root.iter("itemList")]
    return root.findtext(".//headerCd", ""), root.findtext(".//headerMsg", ""), rows


def seoul_http_error(cd: str) -> bool:
    """키 오류는 headerCd 가 아니라 HTTP 401 로 온다(2026-10-13 실측). 0건과 섞지 않는다."""
    return cd.startswith("HTTP")


def json_shape_of_itemlist(r: Reply) -> str:
    if not r.is_json:
        return "XML"
    items = (r.json().get("msgBody") or {}).get("itemList")
    if items is None:
        return "itemList 없음"
    return f"itemList={'배열' if isinstance(items, list) else type(items).__name__}"


# ── 결과 모으기 ──────────────────────────────────────────────

@dataclass
class Result:
    name: str
    verdict: str = ""
    lines: list[str] = field(default_factory=list)

    def add(self, line: str) -> None:
        self.lines.append(line)
        print(f"    {line}")


@dataclass
class Ctx:
    http: Http
    keys: dict[str, str]
    rounds: int
    interval: float

    @property
    def data_key(self) -> str:
        return self.keys["PUBLIC_DATA_SERVICE_KEY"]


# ── 확인들 ───────────────────────────────────────────────────
# 이름은 docs/metro-api-plan.md §3 0단계 표의 「확인할 것」과 짝을 맞춘다.

def check_seoul_https(ctx: Ctx, res: Result) -> None:
    try:
        socket.create_connection(("ws.bus.go.kr", 443), timeout=8).close()
        res.verdict = "443 열림 — https 로 getStationByPos 를 불러 볼 차례"
        r = ctx.http.get("https://ws.bus.go.kr/api/rest/stationinfo/getStationByPos",
                         {"serviceKey": ctx.keys.get("PUBLIC_DATA_SERVICE_KEY", "x"),
                          "tmX": SEOUL_CITY_HALL[1], "tmY": SEOUL_CITY_HALL[0], "radius": 300},
                         label="seoul_https")
        res.add(f"https 응답 HTTP {r.status}")
    except OSError as e:
        res.verdict = "https 불가 (443 연결 안 됨)"
        res.add(f"{type(e).__name__}: {e}")


def seoul(ctx: Ctx, path: str, label: str, **params: Any) -> Reply:
    return ctx.http.get(SEOUL_BUS + path, {"serviceKey": ctx.data_key, **params}, label=label)


def check_seoul_json(ctx: Ctx, res: Result) -> None:
    """서울 4종을 정류소 → 노선 → 경유정류소 → 도착 → 위치 순으로 이어 부른다."""
    lat, lng = SEOUL_CITY_HALL
    xml = seoul(ctx, "stationinfo/getStationByPos", "seoul_pos_xml", tmX=lng, tmY=lat, radius=300)
    res.add(f"resultType 없음 → {'JSON' if xml.is_json else 'XML'} (HTTP {xml.status})")

    pos = seoul(ctx, "stationinfo/getStationByPos", "seoul_pos_json",
                tmX=lng, tmY=lat, radius=300, resultType="json")
    cd, msg, stops = seoul_body(pos)
    res.add(f"15000303 getStationByPos json → {'JSON' if pos.is_json else 'XML'}, headerCd={cd} {msg}, "
            f"{len(stops)}건")
    if not stops:
        res.verdict = (f"키 오류 ({cd} {msg}) — 15000303 활용신청 확인" if seoul_http_error(cd)
                       else f"정류소 0건이라 더 진행 못 함 (headerCd={cd})")
        return
    stop = stops[0]
    res.add(f"  첫 정류소: {stop.get('stationNm')} stationId={stop.get('stationId')} arsId={stop.get('arsId')}")

    via = seoul(ctx, "stationinfo/getRouteByStation", "seoul_route_by_station",
                arsId=stop.get("arsId"), resultType="json")
    cd, msg, routes = seoul_body(via)
    res.add(f"15000303 getRouteByStation json → {'JSON' if via.is_json else 'XML'}, headerCd={cd}, {len(routes)}건")
    if not routes:
        res.verdict = "경유 노선 0건"
        return
    route = routes[0]
    route_id = route.get("busRouteId")
    res.add(f"  첫 노선: {route.get('busRouteNm')} busRouteId={route_id}")

    line = seoul(ctx, "busRouteInfo/getStaionByRoute", "seoul_stations_on_route",
                 busRouteId=route_id, resultType="json")
    cd, msg, on_route = seoul_body(line)
    res.add(f"15000193 getStaionByRoute json → {'JSON' if line.is_json else 'XML'}, headerCd={cd}, {len(on_route)}건")
    ord_ = next((s.get("seq") for s in on_route if str(s.get("station")) == str(stop.get("stationId"))), None)
    res.add(f"  이 정류소의 순번(seq) = {ord_}  ← getArrInfoByRoute 의 ord")

    arr = seoul(ctx, "arrive/getArrInfoByRoute", "seoul_arrival",
                stId=stop.get("stationId"), busRouteId=route_id, ord=ord_ or 1, resultType="json")
    cd, msg, arrivals = seoul_body(arr)
    res.add(f"15000314 getArrInfoByRoute json → {'JSON' if arr.is_json else 'XML'}, headerCd={cd} {msg}, "
            f"{json_shape_of_itemlist(arr)}")
    if arrivals:
        a = arrivals[0]
        res.add(f"  vehId1={a.get('vehId1')} exps1={a.get('exps1')} busType1={a.get('busType1')} "
                f"arrmsg1={a.get('arrmsg1')}")

    bus = seoul(ctx, "buspos/getBusPosByRtid", "seoul_bus_positions", busRouteId=route_id, resultType="json")
    cd, msg, buses = seoul_body(bus)
    types = sorted({str(b.get("busType")) for b in buses})
    res.add(f"15000332 getBusPosByRtid json → {'JSON' if bus.is_json else 'XML'}, headerCd={cd}, {len(buses)}대, "
            f"busType 값 {types}")

    all_json = all(r.is_json for r in (pos, via, line, arr, bus))
    res.verdict = "4종 모두 JSON" if all_json else "JSON 이 아닌 응답이 있음 — 위 줄 확인"


def check_seoul_outside(ctx: Ctx, res: Result) -> None:
    counts = []
    for name, (lat, lng) in (("광명시청", GWANGMYEONG_CITY_HALL), ("수원시청", SUWON_CITY_HALL)):
        r = seoul(ctx, "stationinfo/getStationByPos", f"seoul_pos_{name}",
                  tmX=lng, tmY=lat, radius=500, resultType="json")
        cd, msg, stops = seoul_body(r)
        counts.append(f"{name} {cd}" if seoul_http_error(cd) else f"{name} {len(stops)}건")
        res.add(f"{name}: headerCd={cd} {msg}, {len(stops)}건 "
                f"{[s.get('stationNm') for s in stops[:5]]}")
    res.verdict = ", ".join(counts)


def check_pathinfo(ctx: Ctx, res: Result) -> None:
    verdicts = []
    for short, desc, (slat, slng), (elat, elng) in PAIRS:
        name = f"{short} ({desc})"
        r = seoul(ctx, "pathinfo/getPathInfoByBusNSub", f"pathinfo_{short}",
                  startX=slng, startY=slat, endX=elng, endY=elat, resultType="json")
        cd, msg, paths = seoul_body(r)
        res.add(f"{name}: {'JSON' if r.is_json else 'XML'}, headerCd={cd} {msg}, 경로 {len(paths)}개")
        for p in paths[:3]:
            legs = p.get("pathList") or []
            if isinstance(legs, dict):
                legs = [legs]
            kinds = ["지하철" if leg.get("railLinkList") else "버스" for leg in legs]
            names = [f"{leg.get('routeNm')}({leg.get('fname')}→{leg.get('tname')})" for leg in legs]
            res.add(f"  {p.get('time')}분 {p.get('distance')}m  {'+'.join(kinds)}  {' / '.join(names)}")
        verdicts.append(f"{short} {cd}" if seoul_http_error(cd) else f"{short} 경로 {len(paths)}개 (headerCd={cd})")
    res.verdict = ", ".join(verdicts)


def check_kakao_transit(ctx: Ctx, res: Result) -> None:
    verdicts = []
    for short, desc, (slat, slng), (elat, elng) in PAIRS:
        name = f"{short} ({desc})"
        r = ctx.http.get("https://dapi.kakao.com/v2/routing/publictraffic",
                         {"start_x": slng, "start_y": slat, "end_x": elng, "end_y": elat},
                         headers={"Authorization": f"KakaoAK {ctx.keys['KAKAO_REST_API_KEY']}"},
                         label=f"kakao_{short}")
        res.add(f"{name}: HTTP {r.status}, {len(r.text)}바이트, 앞부분 {r.text[:200]!r}")
        verdicts.append(f"{short} HTTP {r.status}")
    res.verdict = ", ".join(verdicts) + " — 응답 구조는 원본 파일로 확인"


def check_google_transit(ctx: Ctx, res: Result) -> None:
    verdicts = []
    for short, desc, (slat, slng), (elat, elng) in PAIRS:
        name = f"{short} ({desc})"
        body = {
            "origin": {"location": {"latLng": {"latitude": slat, "longitude": slng}}},
            "destination": {"location": {"latLng": {"latitude": elat, "longitude": elng}}},
            "travelMode": "TRANSIT",
            "languageCode": "ko",
        }
        r = ctx.http.post_json(
            "https://routes.googleapis.com/directions/v2:computeRoutes", body,
            {"X-Goog-Api-Key": ctx.keys["GOOGLE_MAPS_API_KEY"],
             "X-Goog-FieldMask": "routes.duration,routes.legs.steps.travelMode,"
                                 "routes.legs.steps.transitDetails.transitLine.nameShort"},
            label=f"google_{short}")
        routes = r.json().get("routes", []) if r.is_json and r.status == 200 else []
        res.add(f"{name}: HTTP {r.status}, 경로 {len(routes)}개")
        for route in routes[:2]:
            steps = [s for leg in route.get("legs", []) for s in leg.get("steps", [])]
            lines = [((s.get("transitDetails") or {}).get("transitLine") or {}).get("nameShort")
                     for s in steps if s.get("travelMode") == "TRANSIT"]
            res.add(f"  {route.get('duration')} 대중교통 {lines}")
        if r.status != 200:
            res.add(f"  {r.text[:300]!r}")
        verdicts.append(f"{short} 경로 {len(routes)}개" if r.status == 200 else f"{short} HTTP {r.status}")
    res.verdict = ", ".join(verdicts)


def gw(ctx: Ctx, path: str, label: str, **params: Any) -> Reply:
    return ctx.http.get(GW + path, {"serviceKey": ctx.data_key, **params}, label=label)


def check_hira(ctx: Ctx, res: Result) -> None:
    """심평원 병원·약국: 이름 + 반경 검색, items 세 모양, 거리순 여부."""
    lat, lng = SEOUL_CITY_HALL
    cases = [
        ("병원 N건: 성모 3km", "B551182/hospInfoServicev2/getHospBasisList",
         dict(yadmNm="성모", xPos=lng, yPos=lat, radius=3000)),
        ("병원 1건 기대: 서울의료원 500m", "B551182/hospInfoServicev2/getHospBasisList",
         dict(yadmNm="서울의료원", xPos=SEOUL_MEDICAL_CENTER[1], yPos=SEOUL_MEDICAL_CENTER[0], radius=500)),
        ("병원 0건 기대: 노원 피부과", "B551182/hospInfoServicev2/getHospBasisList",
         dict(yadmNm="노원 피부과", xPos=lng, yPos=lat, radius=3000)),
        ("약국: 온누리 1km", "B551182/pharmacyInfoService/getParmacyBasisList",
         dict(yadmNm="온누리", xPos=lng, yPos=lat, radius=1000)),
    ]
    ok = 0
    for name, path, params in cases:
        r = gw(ctx, path, f"hira_{name}", numOfRows=20, pageNo=1, _type="json", **params)
        err = gw_error(r)
        if err or not r.is_json:
            res.add(f"{name}: HTTP {r.status} {'JSON' if r.is_json else 'XML'} 오류={err}")
            continue
        ok += 1
        shape, items, total = gw_items(r.json())
        dists = [float(i.get("distance") or 0) for i in items]
        res.add(f"{name}: totalCount={total}, {shape}, 거리순={dists == sorted(dists)}")
        for i in items[:3]:
            x = i.get("XPos", i.get("xPos"))   # 병원은 XPos, 약국 명세는 xPos
            res.add(f"  {i.get('yadmNm')} · {i.get('clCdNm', '')} · {i.get('distance')}m · x={x}")
    xml = gw(ctx, "B551182/hospInfoServicev2/getHospBasisList", "hira_xml_default",
             numOfRows=1, yadmNm="성모", xPos=lng, yPos=lat, radius=3000)
    res.add(f"_type 없음 → {'JSON' if xml.is_json else 'XML'}")
    res.verdict = f"{ok}/{len(cases)} 응답 정상" + ("" if ok else " — 활용신청(15001698·15001673) 확인")


def check_nmc_encoding(ctx: Ctx, res: Result) -> None:
    """국립중앙의료원 한글 필터 0건의 원인. 앱과 같은 1회 인코딩으로 서울·강원을 함께 본다."""
    path = "B552657/HsptlAsembySearchService/getHsptlMdcncListInfoInqire"
    cases = [
        ("필터 없음", {}),
        ("Q0=서울특별시", {"Q0": "서울특별시"}),
        ("Q0=서울특별시 Q1=중구", {"Q0": "서울특별시", "Q1": "중구"}),
        ("Q0=강원특별자치도 Q1=인제군 (2차 근거: 29건)", {"Q0": "강원특별자치도", "Q1": "인제군"}),
    ]
    totals = []
    for name, params in cases:
        r = gw(ctx, path, f"nmc_{name}", numOfRows=1, pageNo=1, _type="json", **params)
        err = gw_error(r)
        total = None if err or not r.is_json else gw_items(r.json())[2]
        query = r.url.split("?", 1)[1]
        res.add(f"{name}: totalCount={total} 오류={err}")
        res.add(f"  보낸 쿼리: {query}")
        totals.append(total)
    res.verdict = " / ".join(str(t) for t in totals) + " (필터 없음 / 서울 / 서울 중구 / 강원 인제)"


def check_tago_city(ctx: Ctx, res: Result) -> None:
    r = gw(ctx, "1613000/BusSttnInfoInqireService/getCtyCodeList", "tago_city_codes", _type="json")
    err = gw_error(r)
    if err or not r.is_json:
        res.verdict = f"오류 {err}"
        return
    _, items, total = gw_items(r.json())
    metro = [i for i in items if re.search("인천|서울|경기|고양|수원|성남|용인|부천|안양", str(i.get("cityname")))]
    for i in metro:
        res.add(f"cityCode={i.get('citycode')} {i.get('cityname')}")
    incheon = [i for i in items if "인천" in str(i.get("cityname"))]
    # getCtyCodeList 는 totalCount 를 주지 않는다(2026-10-14 실측)
    res.verdict = (f"인천 cityCode={incheon[0].get('citycode')}" if incheon else "인천 없음") + f" (전체 {len(items)}개)"


def check_gbis_vs_tago(ctx: Ctx, res: Result) -> None:
    """수원시청 근처 경기 정류소 하나를 TAGO 와 GBIS 로 동시에 불러 도착 예정 초를 비교한다."""
    lat, lng = SUWON_CITY_HALL
    near = gw(ctx, "1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList", "tago_near_suwon",
              gpsLati=lat, gpsLong=lng, numOfRows=20, pageNo=1, _type="json")
    _, stops, _ = gw_items(near.json()) if near.is_json else ("", [], None)
    stop = next((s for s in stops if str(s.get("nodeid", "")).startswith("GGB")), None)
    if not stop:
        res.verdict = f"GGB 로 시작하는 경기 정류소를 못 찾음 (오류={gw_error(near)})"
        return
    node_id, city = stop["nodeid"], stop["citycode"]
    station_id = node_id[3:]   # TAGO 경기 nodeid = "GGB" + GBIS stationId 라는 가정. 이 확인으로 검증한다
    res.add(f"정류소 {stop.get('nodenm')} TAGO nodeid={node_id} cityCode={city} → GBIS stationId={station_id}")

    diffs: list[int] = []
    stop_lags: list[int] = []
    for n in range(ctx.rounds):
        tago = gw(ctx, "1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", f"tago_arr_{n}",
                  cityCode=city, nodeId=node_id, numOfRows=50, pageNo=1, _type="json")
        gbis = gw(ctx, "6410000/busarrivalservice/v2/getBusArrivalListv2", f"gbis_arr_{n}",
                  stationId=station_id, format="json")
        _, t_items, _ = gw_items(tago.json()) if tago.is_json else ("", [], None)
        t_by_route: dict[str, tuple[int, int]] = {}   # 노선 → (초, 남은 정거장). 두 대면 먼저 오는 쪽
        for i in t_items:
            key, sec = str(i.get("routeno")), int(i.get("arrtime") or 0)
            if key not in t_by_route or sec < t_by_route[key][0]:
                t_by_route[key] = (sec, int(i.get("arrprevstationcnt") or 0))
        g_list: list[dict] = []
        if gbis.is_json:
            body = (gbis.json().get("response") or {}).get("msgBody") or {}
            g_list = body.get("busArrivalList") or []
            if isinstance(g_list, dict):
                g_list = [g_list]
        else:
            res.add(f"  GBIS 응답이 JSON 이 아님: {gbis.text[:160]!r}")
        row = []
        for g in g_list:
            route = str(g.get("routeName"))
            sec = g.get("predictTimeSec1")
            if route in t_by_route and sec not in (None, ""):
                t_sec, t_stops = t_by_route[route]
                d, lag = t_sec - int(sec), t_stops - int(g.get("locationNo1") or 0)
                # 한쪽에만 있는 차량을 짝지으면 수십 분 차이가 난다(2026-10-14 실측 −1988초). 따로 센다
                if abs(lag) > 5:
                    row.append(f"{route}:다른 차량({t_stops}·{g.get('locationNo1')}정거장)")
                    continue
                diffs.append(d)
                stop_lags.append(lag)
                row.append(f"{route}:{d:+d}초/{lag:+d}정거장")
        res.add(f"{n + 1}회차 (TAGO−GBIS): TAGO {len(t_by_route)}노선, GBIS {len(g_list)}노선 → {' '.join(row[:8])}")
        if n + 1 < ctx.rounds:
            time.sleep(ctx.interval)
    if diffs:
        diffs.sort()
        stop_lags.sort()
        res.verdict = (f"TAGO−GBIS 중앙값 {diffs[len(diffs) // 2]:+d}초·{stop_lags[len(stop_lags) // 2]:+d}정거장, "
                       f"범위 {diffs[0]:+d}~{diffs[-1]:+d}초 ({len(diffs)}쌍)")
    else:
        res.verdict = "비교할 쌍이 없음 — 원본 파일 확인"


@dataclass
class Check:
    name: str
    title: str            # metro-api-plan.md 0단계 표의 「확인할 것」
    needs: tuple[str, ...]
    run: Callable[[Ctx, Result], None]


CHECKS = [
    Check("seoul-https", "ws.bus.go.kr 이 https 를 받는가", (), check_seoul_https),
    Check("seoul-json", "서울 4종 resultType=json", ("PUBLIC_DATA_SERVICE_KEY",), check_seoul_json),
    Check("pathinfo", "15000414 의 응답·범위", ("PUBLIC_DATA_SERVICE_KEY",), check_pathinfo),
    Check("kakao-transit", "카카오 대중교통 경로", ("KAKAO_REST_API_KEY",), check_kakao_transit),
    Check("google-transit", "Google Routes TRANSIT 이 한국에서 나오는가", ("GOOGLE_MAPS_API_KEY",),
          check_google_transit),
    Check("hira", "심평원 yadmNm + 반경 검색", ("PUBLIC_DATA_SERVICE_KEY",), check_hira),
    Check("nmc-encoding", "국립중앙의료원 0건의 원인", ("PUBLIC_DATA_SERVICE_KEY",), check_nmc_encoding),
    Check("tago-city", "인천 cityCode", ("PUBLIC_DATA_SERVICE_KEY",), check_tago_city),
    Check("gbis-vs-tago", "경기 TAGO 와 GBIS 의 도착시각", ("PUBLIC_DATA_SERVICE_KEY",), check_gbis_vs_tago),
    Check("seoul-outside", "서울 API 가 경기 쪽 정류소를 주는가", ("PUBLIC_DATA_SERVICE_KEY",), check_seoul_outside),
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="쉼표로 구분한 확인 이름 (--list 참고)")
    ap.add_argument("--list", action="store_true", help="확인 목록만 보여 준다")
    ap.add_argument("--out", type=Path, default=ROOT / "build" / "metro-probe", help="원본 응답 저장 위치")
    ap.add_argument("--rounds", type=int, default=3, help="gbis-vs-tago 반복 횟수")
    ap.add_argument("--interval", type=float, default=30.0, help="gbis-vs-tago 반복 간격(초)")
    args = ap.parse_args()

    if args.list:
        for c in CHECKS:
            print(f"{c.name:16} {c.title}  (키: {', '.join(c.needs) or '없음'})")
        return 0

    selected = CHECKS
    if args.only:
        wanted = {s.strip() for s in args.only.split(",")}
        unknown = wanted - {c.name for c in CHECKS}
        if unknown:
            print(f"모르는 확인: {', '.join(sorted(unknown))}. --list 로 이름을 보세요.", file=sys.stderr)
            return 2
        selected = [c for c in CHECKS if c.name in wanted]

    keys = load_keys()
    args.out.mkdir(parents=True, exist_ok=True)
    ctx = Ctx(Http(args.out, list(keys.values())), keys, max(1, args.rounds), args.interval)
    print(f"키: {', '.join(sorted(keys)) or '없음'}   원본 응답: {args.out}\n")

    results = []
    for c in selected:
        res = Result(c.title)
        missing = [k for k in c.needs if k not in keys]
        print(f"■ {c.name} — {c.title}")
        if missing:
            res.verdict = f"건너뜀 ({', '.join(missing)} 없음)"
        else:
            try:
                c.run(ctx, res)
            except Exception as e:   # 확인 하나가 깨져도 나머지는 돌린다
                res.verdict = f"스크립트 오류 {type(e).__name__}: {e}"
        print(f"  → {res.verdict}\n")
        results.append(res)

    print("| 확인할 것 | 결과 |\n|---|---|")
    for res in results:
        print(f"| {res.name} | {res.verdict} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
