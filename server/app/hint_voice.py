"""The question bank's hints read aloud once and kept for good, so a hint never waits on a speech call or on the day's
speech limit.

Every quiz hint the car can say is a bank hint, word for word (service.pick_questions), so the hints can be recorded
ahead of time. They are kept by their words alone, not by model: a hint recorded with the Lite model is played even
when TTS_MODEL names another one. /v1/speak looks here first. The folder is on the data volume and never pruned.

    docker compose exec story python -m app.hint_voice              how many hints are kept, nothing is called
    docker compose exec story python -m app.hint_voice --yes        records up to 80 more (TTS_PAID_RPM a minute)
    docker compose exec story python -m app.hint_voice --yes --max 30

The Lite model has its own daily limit (100 a day on the paid key), apart from the story voice's. A daily 429 ends the
run; what was recorded is kept, and the next run carries on from the first hint still missing.
"""
import argparse
import hashlib
import logging
import os
import sys
from pathlib import Path

from .bank import Bank
from .pacing import is_daily, is_quota
from .service import tidy_line
from .voice import QuotaError, Voice, make_voice, respelled

log = logging.getLogger("hint_voice")

HINT_MODEL = "gemini-3.8-flash-lite-tts"
# a run stops a little short of the Lite model's 100 a day: a line heard back wrong is made again (voice.LISTEN_TRIES)
DEFAULT_MAX = 80
# this many failures in a row (not the quota) end the run: something is wrong that more calls will not fix
FAILS_IN_A_ROW = 3


def hint_key(text: str) -> str:
    """The hint's words as the server says them, nothing else: the same file whatever model or style is set."""
    return hashlib.sha256(f"hint\n{respelled(tidy_line(text))}".encode("utf-8")).hexdigest()


class HintVoices:
    def __init__(self, folder: Path):
        self.folder = folder

    def path(self, text: str) -> Path:
        return self.folder / f"{hint_key(text)}.wav"

    def get(self, text: str) -> bytes | None:
        try:
            p = self.path(text)
            return p.read_bytes() if p.is_file() else None
        except OSError:
            return None

    def put(self, text: str, data: bytes) -> None:
        self.folder.mkdir(parents=True, exist_ok=True)
        p = self.path(text)
        tmp = p.with_suffix(".tmp")
        tmp.write_bytes(data)
        tmp.replace(p)

    def missing(self, lines: list[str]) -> list[str]:
        return [line for line in lines if not self.path(line).is_file()]


def hint_lines(bank: Bank) -> list[str]:
    """Every different hint in the bank, in the bank's order."""
    seen: dict[str, None] = {}
    for e in bank.entries:
        line = tidy_line(e.hint)
        if line:
            seen.setdefault(line, None)
    return list(seen)


def record(voice: Voice, store: HintVoices, lines: list[str], max_lines: int) -> dict:
    """Records the missing hints, at most [max_lines], and keeps each as soon as it is made."""
    made, failed, stopped = 0, 0, ""
    in_a_row = 0
    for line in store.missing(lines):
        if made >= max_lines:
            stopped = f"--max {max_lines}"
            break
        try:
            data = voice.speak(line)
        except QuotaError as e:
            # the free key's quota guard: the voice tried the paid key already (KeyedVoice) or there is none
            stopped = f"quota ({e})"
            break
        except Exception as e:
            if is_quota(e) and is_daily(e):
                stopped = "daily limit reached"
                break
            failed += 1
            in_a_row += 1
            log.warning("hint_voice: failed (%s): %s", type(e).__name__, line[:40])
            if in_a_row >= FAILS_IN_A_ROW:
                stopped = f"{FAILS_IN_A_ROW} failures in a row ({type(e).__name__})"
                break
            continue
        store.put(line, data)
        made += 1
        in_a_row = 0
        log.info("hint_voice: %d made: %s", made, line[:40])
    return {"made": made, "failed": failed, "stopped": stopped, "left": len(store.missing(lines))}


def hint_voice(model: str = HINT_MODEL) -> Voice | None:
    """The server's voice on the Lite model: the free key first, then the paid key at TTS_PAID_RPM a minute."""
    return make_voice(model)


def folder() -> Path:
    return Path(os.environ.get("STORY_DATA_DIR", "data")) / "hint_voice"


def main(argv: list[str] | None = None) -> int:
    from .bank import load

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--yes", action="store_true", help="really make the calls")
    ap.add_argument("--max", type=int, default=DEFAULT_MAX, help="hints to record in this run")
    ap.add_argument("--model", default=HINT_MODEL)
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    lines = hint_lines(load())
    store = HintVoices(folder())
    left = store.missing(lines)
    print(f"hints: {len(lines)}, kept: {len(lines) - len(left)}, missing: {len(left)} ({store.folder})")
    if not args.yes or not left:
        return 0
    voice = hint_voice(args.model)
    if voice is None:
        print("no GEMINI_API_KEY or GEMINI_API_KEY_PAID")
        return 1
    result = record(voice, store, lines, args.max)
    print(f"made {result['made']}, failed {result['failed']}, still missing {result['left']}"
          + (f", stopped: {result['stopped']}" if result["stopped"] else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
