"""
음성 클립 프로토타입 샘플 생성.

세 가지를 확인하려는 것이다.
  1. 화자별 음질 — 어느 목소리가 어르신에게 사람처럼 들리는가
  2. 이어붙이기 이음매 — 조각을 합쳤을 때 끊겨 들리는가 (이 구조의 진짜 리스크)
  3. 문안 — 해요체로 바꾼 문장이 실제로 들었을 때 자연스러운가

문장은 JourneyController 에서 실제로 말하는 것을 그대로 가져온다.
앱을 고치면 여기도 같이 고쳐야 한다. 듣고 판단하는 것이 목적이라
앱과 다른 문장을 들으면 판단이 무의미해진다.

edge-tts 는 프로토타입 검증용이다. 배포용으로는 쓸 수 없다 (README 참고).

    python tools/make_voice_samples.py
"""

import asyncio
import subprocess
import sys
from pathlib import Path

import edge_tts

OUT = Path(__file__).resolve().parent.parent / "docs" / "voice-samples"

VOICES = [
    ("sunhi", "ko-KR-SunHiNeural"),
    ("injoon", "ko-KR-InJoonNeural"),
    ("hyunsu", "ko-KR-HyunsuMultilingualNeural"),
]

# 앱이 실제로 말하는 문장.
#
# 긴급 3종(벨·하차준비·하차)은 명령형 그대로다. 몇 초 안에 몸을 움직여야 하는
# 안내라 부드럽게 돌려 말하면 판단이 늦어진다. 나머지는 해요체다.
SENTENCES = {
    "01-wait": "칠 공 일 육 번 버스가 삼 분 뒤에 와요. 조금만 기다리시면 돼요. "
               "계단 없는 버스라 타기 편하실 거예요.",
    "02-board": "칠 공 일 육 번 버스가 곧 와요. 손을 드세요.",
    "03-bell": "벨을 누르세요. 다음 정류장에서 내립니다.",
    "04-alight": "중앙시장입니다. 내리세요.",
    "05-walk": "이제 성모의원 쪽으로, 백오십 걸음쯤 걸어가시면 돼요.",
    "06-riding": "세 정거장 남았어요.",
}

# 고친 것을 귀로 확인하기 위한 대비 쌍. 대표 화자로만 만든다.
#
# 왼쪽이 고치기 전, 오른쪽이 고친 뒤다. TTS 는 숫자를 보면 한자어로 읽어서
# "3정거장" 이 "삼 정거장" 으로 나갔다. 정거장은 순우리말 수관형사를 쓰는 단위다.
COMPARISONS = {
    "cmp-before-numeral": "3정거장 남았습니다.",
    "cmp-after-numeral": "세 정거장 남았어요.",
    "cmp-before-tone": "약 이백삼십 걸음 걸어서 성모의원으로 가세요.",
    "cmp-after-tone": "이제 성모의원 쪽으로, 백오십 걸음쯤 걸어가시면 돼요.",
}

# 슬롯 방식으로 쪼갠 조각. 이걸 이어붙여 "01-wait" 와 같은 문장을 만든다.
# 숫자만 슬롯이고 나머지는 통째로 둔다 — 잘게 쪼갤수록 이음매가 늘어난다.
PIECES = [
    ("d7", "칠"),
    ("d0", "공"),
    ("d1", "일"),
    ("d6", "육"),
    ("frag-bus-arrives-in", "번 버스가"),
    ("n3", "삼"),
    ("frag-minutes-later", "분 뒤에 와요. 조금만 기다리시면 돼요."),
    ("frag-lowfloor", "계단 없는 버스라 타기 편하실 거예요."),
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

    # 2. 고치기 전후 대비 (대표 화자만)
    label, voice = VOICES[0]
    for name, text in COMPARISONS.items():
        mp3 = OUT / f"{label}_{name}.mp3"
        await synth(text, voice, mp3)
        print(f"  {mp3.name:32} {duration(mp3):5.2f}s")

    # 3. 이음매 확인 — 조각을 따로 합성해서 이어붙인다
    pieces_dir = OUT / "pieces"
    pieces_dir.mkdir(exist_ok=True)
    # 문안이 바뀌면 조각 목록도 바뀐다. 지난 실행의 조각이 남아 있으면
    # 어느 것이 지금 문장인지 알 수 없다.
    for stale in list(pieces_dir.glob("*.mp3")) + list(pieces_dir.glob("*.wav")):
        stale.unlink()

    trimmed = []
    for pid, text in PIECES:
        mp3 = pieces_dir / f"{pid}.mp3"
        await synth(text, voice, mp3)
        wav = pieces_dir / f"{pid}.wav"
        to_wav(mp3, wav, trim=True)
        trimmed.append(wav)

    stitched = OUT / f"{label}_07-stitched.wav"
    concat(trimmed, stitched)
    print(f"  {stitched.name:32} {duration(stitched):5.2f}s  <- 조각 이어붙인 것")

    whole = OUT / f"{label}_01-wait.mp3"
    print(f"\n비교: 통문장 {duration(whole):.2f}s  vs  이어붙임 {duration(stitched):.2f}s")


if __name__ == "__main__":
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(main())
