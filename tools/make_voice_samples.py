"""
음성 클립 프로토타입 샘플 생성.

두 가지를 확인하려는 것이다.
  1. 화자별 음질 — 어느 목소리가 어르신에게 사람처럼 들리는가
  2. 이어붙이기 이음매 — 조각을 합쳤을 때 끊겨 들리는가 (이 구조의 진짜 리스크)

edge-tts 는 프로토타입 검증용이다. 배포용으로는 쓸 수 없다 (README 참고).
"""

import asyncio
import subprocess
import sys
from pathlib import Path

import edge_tts

OUT = Path(__file__).parent / "voice-samples"

VOICES = [
    ("sunhi", "ko-KR-SunHiNeural"),
    ("injoon", "ko-KR-InJoonNeural"),
    ("hyunsu", "ko-KR-HyunsuMultilingualNeural"),
]

# 앱이 실제로 말하는 문장 (JourneyController 에서 그대로 가져옴)
SENTENCES = {
    "01-wait": "칠 공 일 육 번 버스가 삼 분 뒤에 옵니다. 계단 없는 저상버스입니다.",
    "02-board": "칠 공 일 육 번 버스가 곧 옵니다. 손을 드세요.",
    "03-bell": "벨을 누르세요. 다음 정류장에서 내립니다.",
    "04-alight": "중앙시장입니다. 내리세요.",
}

# 슬롯 방식으로 쪼갠 조각. 이걸 이어붙여 "01-wait" 와 같은 문장을 만든다.
PIECES = [
    ("d7", "칠"),
    ("d0", "공"),
    ("d1", "일"),
    ("d6", "육"),
    ("frag-bus-arrives-in", "번 버스가"),
    ("n3", "삼"),
    ("frag-minutes-later", "분 뒤에 옵니다."),
    ("frag-lowfloor", "계단 없는 저상버스입니다."),
]

# 어르신 기준으로 기본 속도는 빠르다. 앱의 speechRate 0.85 에 대응.
RATE = "-15%"


async def synth(text: str, voice: str, path: Path) -> None:
    await edge_tts.Communicate(text, voice, rate=RATE).save(str(path))


def to_wav(src: Path, dst: Path, trim: bool) -> None:
    """공통 포맷(24kHz 모노)으로 맞춘다. trim 이면 앞뒤 무음을 깎아 이음매를 좁힌다."""
    filters = ["aformat=sample_fmts=s16:sample_rates=24000:channel_layouts=mono"]
    if trim:
        # 앞뒤 무음 제거. -45dB 아래를 무음으로 본다.
        filters.insert(0, "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02")
        filters.insert(1, "areverse")
        filters.insert(2, "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02")
        filters.insert(3, "areverse")
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(src), "-af", ",".join(filters), str(dst)],
        check=True,
    )


def concat(parts: list[Path], dst: Path) -> None:
    listing = dst.with_suffix(".txt")
    listing.write_text("".join(f"file '{p.as_posix()}'\n" for p in parts), encoding="utf-8")
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0",
         "-i", str(listing), "-c", "copy", str(dst)],
        check=True,
    )
    listing.unlink()


def duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", str(path)],
        capture_output=True, text=True, check=True,
    )
    return float(out.stdout.strip())


async def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)

    # 1. 화자별 통문장
    for label, voice in VOICES:
        for name, text in SENTENCES.items():
            mp3 = OUT / f"{label}_{name}.mp3"
            await synth(text, voice, mp3)
            print(f"  {mp3.name:32} {duration(mp3):5.2f}s")

    # 2. 이음매 확인 — 조각을 따로 합성해서 이어붙인다
    pieces_dir = OUT / "pieces"
    pieces_dir.mkdir(exist_ok=True)
    label, voice = VOICES[0]

    trimmed = []
    for pid, text in PIECES:
        mp3 = pieces_dir / f"{pid}.mp3"
        await synth(text, voice, mp3)
        wav = pieces_dir / f"{pid}.wav"
        to_wav(mp3, wav, trim=True)
        trimmed.append(wav)

    stitched = OUT / f"{label}_05-stitched.wav"
    concat(trimmed, stitched)
    print(f"  {stitched.name:32} {duration(stitched):5.2f}s  <- 조각 이어붙인 것")

    whole = OUT / f"{label}_01-wait.mp3"
    print(f"\n비교: 통문장 {duration(whole):.2f}s  vs  이어붙임 {duration(stitched):.2f}s")


if __name__ == "__main__":
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(main())
