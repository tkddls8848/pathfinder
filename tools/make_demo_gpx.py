#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
데스크톱 에뮬레이터에 넣을 가짜 이동 경로를 만든다.

이 앱은 화면을 눌러서 넘기는 앱이 아니다. 위치가 실제로 움직여야
도보 → 정류장 도착 → 대기 로 넘어간다(JourneyController.trackLocation).
그래서 에뮬레이터를 켜 두기만 하면 홈 화면에서 멈춰 있고,
"데스크톱에서는 안 된다" 는 오해가 여기서 생긴다.

이 스크립트는 어르신 보행 속도로 걷고, 정류장에서 기다리고, 버스 속도로
이동하는 좌표열을 시간표와 함께 만들어 준다. 출력은 셋 중 하나:

  gpx  : 에뮬레이터 Extended controls > Location > Import GPX 로 불러와 재생
  kml  : 같은 화면에서 KML 로 불러올 때
  sh   : 창 없는(headless) 데스크톱용. `adb emu geo fix` 를 sleep 과 함께 뿌린다

사용 예:

  # 기본 시나리오(README 의 7016번 예시)를 GPX 로
  python3 tools/make_demo_gpx.py --out demo.gpx

  # 창 없이 셸로 재생, 5배속
  python3 tools/make_demo_gpx.py --format sh --speed 5 --out demo.sh && bash demo.sh

  # 내 경로로. 구간은 walk|wait|bus 중 하나
  python3 tools/make_demo_gpx.py --leg walk:37.6100,126.9290 \
      --leg wait:37.6086,126.9305 --leg bus:37.6011,126.9412 \
      --leg walk:37.6004,126.9425
"""

from __future__ import annotations

import argparse
import datetime as dt
import math
import sys
from dataclasses import dataclass
from typing import Iterator
from xml.sax.saxutils import escape

# 지구 반지름(m). 정류장 판정 반경이 35m 라 구면 근사로 충분하다.
EARTH_RADIUS_M = 6_371_000.0

# 어르신 보행 속도. 앱이 도보 시간을 계산할 때 쓰는 값(0.65m 걸음)과 결이 같다.
DEFAULT_WALK_MPS = 0.9

# 시내버스 표정속도. 정차를 포함한 평균이라 주행 최고속도보다 낮다.
DEFAULT_BUS_MPS = 6.5

MODES = ("walk", "wait", "bus")


@dataclass(frozen=True)
class Leg:
    """한 구간의 끝점과, 그 끝점까지 가는 방식."""

    mode: str
    lat: float
    lon: float


# 기본 시나리오: README 의 "집 → 걸어서 3분 → 7016 버스 → 걸어서 2분 → 성모의원".
# 서울 은평구 일대의 실제 좌표 범위를 쓴다. 정류소 이름은 앱이 API 로 받으므로
# 여기서는 좌표만 맞으면 된다.
DEFAULT_LEGS = (
    Leg("walk", 37.61000, 126.92900),  # 집에서 출발해 정류장 쪽으로
    Leg("wait", 37.60860, 126.93050),  # 승차 정류장 — 여기서 버스를 기다린다
    Leg("bus", 37.60110, 126.94120),   # 버스로 이동해 하차 정류장
    Leg("walk", 37.60040, 126.94250),  # 하차 후 목적지까지 도보
)


def haversine_m(a_lat: float, a_lon: float, b_lat: float, b_lon: float) -> float:
    """두 좌표 사이 거리(m). 앱의 Geo.distanceMeters 와 같은 방식."""
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    d_lat = p2 - p1
    d_lon = math.radians(b_lon - a_lon)
    h = math.sin(d_lat / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(d_lon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def sample_points(
    legs: tuple[Leg, ...],
    walk_mps: float,
    bus_mps: float,
    wait_seconds: float,
    interval: float,
) -> Iterator[tuple[float, float, float]]:
    """(경과초, 위도, 경도) 를 순서대로 낸다.

    직선 보간이다. 실제 버스는 도로를 따라 돌지만, 앱이 승차 구간에서 보는 것은
    GPS 가 아니라 실시간 차량 순번(nodeord)이므로 여기서 도로를 따를 이유가 없다.
    도보 구간만 거리 판정에 쓰이고, 그건 직선 보간으로 충분하다.
    """
    if not legs:
        return
    elapsed = 0.0
    cur = legs[0]
    yield (elapsed, cur.lat, cur.lon)

    for nxt in legs[1:]:
        if nxt.mode == "wait":
            # 정류장까지는 걸어가고, 도착한 자리에서 wait_seconds 동안 서 있는다.
            # 앱이 "정류장 도착" 을 판정하고 도착정보를 폴링할 시간을 준다.
            elapsed = yield from _walk_segment(cur, nxt, walk_mps, interval, elapsed)
            held = 0.0
            while held < wait_seconds:
                held += interval
                elapsed += interval
                # 제자리라도 점을 계속 보내야 한다. 위치 갱신이 끊기면
                # 에뮬레이터가 마지막 좌표를 유지하긴 하지만, 재생 시간표가
                # 어긋나 다음 구간이 너무 일찍 시작한다.
                yield (elapsed, nxt.lat, nxt.lon)
        else:
            speed = bus_mps if nxt.mode == "bus" else walk_mps
            elapsed = yield from _walk_segment(cur, nxt, speed, interval, elapsed)
        cur = nxt


def _walk_segment(
    frm: Leg,
    to: Leg,
    speed_mps: float,
    interval: float,
    elapsed: float,
) -> Iterator[tuple[float, float, float]]:
    """한 구간을 speed_mps 로 지나가며 interval 초마다 점을 낸다. 끝난 경과초를 돌려준다."""
    distance = haversine_m(frm.lat, frm.lon, to.lat, to.lon)
    duration = distance / speed_mps if speed_mps > 0 else 0.0
    steps = max(1, math.ceil(duration / interval))
    for i in range(1, steps + 1):
        t = i / steps
        elapsed += duration / steps
        yield (elapsed, lerp(frm.lat, to.lat, t), lerp(frm.lon, to.lon, t))
    return elapsed


def as_gpx(points: list[tuple[float, float, float]], start: dt.datetime, name: str) -> str:
    head = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<gpx version="1.1" creator="eodiga/make_demo_gpx.py"\n'
        '     xmlns="http://www.topografix.com/GPX/1/1">\n'
        f"  <trk><name>{escape(name)}</name><trkseg>\n"
    )
    body = "".join(
        f'    <trkpt lat="{lat:.6f}" lon="{lon:.6f}">'
        f"<time>{_iso(start, sec)}</time></trkpt>\n"
        for sec, lat, lon in points
    )
    return head + body + "  </trkseg></trk>\n</gpx>\n"


def as_kml(points: list[tuple[float, float, float]], start: dt.datetime, name: str) -> str:
    # 에뮬레이터는 KML 에서 gx:Track 의 when/coord 쌍을 읽어 시간표대로 재생한다.
    whens = "".join(f"      <when>{_iso(start, sec)}</when>\n" for sec, _, _ in points)
    coords = "".join(f"      <gx:coord>{lon:.6f} {lat:.6f} 0</gx:coord>\n" for _, lat, lon in points)
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<kml xmlns="http://www.opengis.net/kml/2.2"\n'
        '     xmlns:gx="http://www.google.com/kml/ext/2.2">\n'
        f"  <Placemark><name>{escape(name)}</name>\n"
        "    <gx:Track>\n" + whens + coords + "    </gx:Track>\n"
        "  </Placemark>\n</kml>\n"
    )


def as_shell(points: list[tuple[float, float, float]], serial: str | None) -> str:
    """창 없는 데스크톱용. adb 로 좌표를 하나씩 밀어 넣는다.

    `adb emu geo fix` 는 경도를 먼저 받는다. 순서를 바꿔 쓰면 좌표가
    바다 한가운데로 가고, 앱은 "정류장이 멀다" 고만 말한다.
    """
    target = f"adb -s {serial} emu" if serial else "adb emu"
    lines = [
        "#!/usr/bin/env bash",
        "# make_demo_gpx.py 가 만든 위치 재생 스크립트. 에뮬레이터가 떠 있어야 한다.",
        "set -euo pipefail",
        "",
    ]
    prev = 0.0
    for sec, lat, lon in points:
        gap = round(sec - prev, 2)
        if gap > 0:
            lines.append(f"sleep {gap}")
        lines.append(f"{target} geo fix {lon:.6f} {lat:.6f}")
        prev = sec
    lines.append("")
    return "\n".join(lines)


def _iso(start: dt.datetime, offset_seconds: float) -> str:
    stamp = start + dt.timedelta(seconds=offset_seconds)
    return stamp.strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_leg(raw: str) -> Leg:
    mode, _, coords = raw.partition(":")
    mode = mode.strip().lower()
    if mode not in MODES:
        raise argparse.ArgumentTypeError(f"구간 종류는 {'|'.join(MODES)} 중 하나여야 합니다: {raw}")
    try:
        lat_s, lon_s = coords.split(",")
        lat, lon = float(lat_s), float(lon_s)
    except ValueError:
        raise argparse.ArgumentTypeError(f"좌표는 위도,경도 형식이어야 합니다: {raw}") from None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        raise argparse.ArgumentTypeError(f"좌표 범위를 벗어났습니다: {raw}")
    return Leg(mode, lat, lon)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="에뮬레이터에 넣을 가짜 이동 경로를 만든다.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--format", choices=("gpx", "kml", "sh"), default="gpx")
    parser.add_argument("--out", help="출력 파일. 없으면 표준출력.")
    parser.add_argument(
        "--leg",
        action="append",
        type=parse_leg,
        metavar="종류:위도,경도",
        help="구간을 순서대로 준다. walk|wait|bus. 하나도 안 주면 기본 시나리오.",
    )
    parser.add_argument("--walk-speed", type=float, default=DEFAULT_WALK_MPS, help="m/s")
    parser.add_argument("--bus-speed", type=float, default=DEFAULT_BUS_MPS, help="m/s")
    parser.add_argument(
        "--wait-seconds",
        type=float,
        default=90.0,
        help="정류장에서 서 있는 시간(초). 도착정보 폴링을 볼 시간을 준다.",
    )
    parser.add_argument("--interval", type=float, default=3.0, help="좌표 간격(초). 앱의 위치 스트림과 같은 3초가 기본.")
    parser.add_argument(
        "--speed",
        type=float,
        default=1.0,
        help="재생 배속. 2 면 시간표가 절반으로 줄어 두 배 빨리 지나간다.",
    )
    parser.add_argument("--serial", help="--format sh 에서 쓸 adb 기기 이름(예: emulator-5554).")
    parser.add_argument("--name", default="어디가요 데모 경로")
    args = parser.parse_args(argv)

    for value, label in ((args.walk_speed, "--walk-speed"), (args.bus_speed, "--bus-speed")):
        if value <= 0:
            parser.error(f"{label} 는 0 보다 커야 합니다.")
    if args.interval <= 0:
        parser.error("--interval 은 0 보다 커야 합니다.")
    if args.speed <= 0:
        parser.error("--speed 는 0 보다 커야 합니다.")

    legs = tuple(args.leg) if args.leg else DEFAULT_LEGS
    if len(legs) < 2:
        parser.error("구간은 최소 두 개(출발점과 도착점)가 필요합니다.")

    points = [
        (sec / args.speed, lat, lon)
        for sec, lat, lon in sample_points(
            legs,
            walk_mps=args.walk_speed,
            bus_mps=args.bus_speed,
            wait_seconds=args.wait_seconds,
            interval=args.interval,
        )
    ]

    start = dt.datetime(2026, 1, 1, 9, 0, 0)
    if args.format == "gpx":
        text = as_gpx(points, start, args.name)
    elif args.format == "kml":
        text = as_kml(points, start, args.name)
    else:
        text = as_shell(points, args.serial)

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
        total = points[-1][0] if points else 0.0
        print(
            f"{args.out} — 좌표 {len(points)}개, 재생 시간 {total / 60:.1f}분",
            file=sys.stderr,
        )
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
