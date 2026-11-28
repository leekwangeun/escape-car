"""The bank's hints recorded ahead of time: kept by their words alone, played by /v1/speak without a call, and recorded
a run at a time that stops at the daily limit and carries on where it stopped."""
from fastapi.testclient import TestClient

from app.hint_voice import HintVoices, hint_key, hint_lines, record
from app.main import create_app
from app.voice import QuotaError
from tests.fakes import BANK, FakeWriter
from tests.test_voice import INSTALL, FakeVoice


class DailyLimit(Exception):
    code = 429

    def __str__(self) -> str:
        return "429 RESOURCE_EXHAUSTED: GenerateRequestsPerDayPerProjectPerModel"


class LimitedVoice(FakeVoice):
    """Says [ok] lines, then answers like the model past its daily limit."""

    def __init__(self, ok: int, error: Exception | None = None):
        super().__init__()
        self.ok = ok
        self.error = error or DailyLimit()

    def speak(self, text: str) -> bytes:
        if len(self.calls) >= self.ok:
            self.calls.append(text)
            raise self.error
        return super().speak(text)


def test_every_different_bank_hint_once_in_bank_order():
    lines = hint_lines(BANK)
    assert lines == list(dict.fromkeys(" ".join(e.hint.split()) for e in BANK.entries))
    assert len(lines) == len(set(lines)) > 0


def test_a_hint_is_kept_by_its_words_whatever_the_spaces():
    a = "먼저 곱하고 나서 빼 봐."
    assert hint_key(a) == hint_key("  먼저 곱하고   나서 빼 봐. ") != hint_key("먼저 빼 봐.")


def test_a_run_records_the_missing_hints_up_to_max_and_the_next_carries_on(tmp_path):
    store = HintVoices(tmp_path / "hint_voice")
    lines = hint_lines(BANK)
    first = record(FakeVoice(), store, lines, max_lines=2)
    assert first["made"] == 2 and first["left"] == len(lines) - 2 and first["stopped"] == "--max 2"
    v = FakeVoice()
    second = record(v, store, lines, max_lines=100)
    assert v.calls == lines[2:]  # nothing kept is made again
    assert second["left"] == 0 and store.missing(lines) == []


def test_the_daily_limit_ends_the_run_and_keeps_what_was_made(tmp_path):
    store = HintVoices(tmp_path / "hint_voice")
    lines = hint_lines(BANK)
    v = LimitedVoice(ok=1)
    r = record(v, store, lines, max_lines=100)
    assert r["made"] == 1 and r["stopped"] == "daily limit reached" and len(v.calls) == 2
    assert store.get(lines[0]) is not None and store.get(lines[1]) is None


def test_the_free_keys_quota_guard_ends_the_run_too(tmp_path):
    r = record(LimitedVoice(ok=0, error=QuotaError("daily free quota used up")), HintVoices(tmp_path / "h"),
               hint_lines(BANK), max_lines=100)
    assert r["made"] == 0 and r["stopped"].startswith("quota")


def test_failures_in_a_row_end_the_run(tmp_path):
    v = LimitedVoice(ok=0, error=RuntimeError("no audio"))
    r = record(v, HintVoices(tmp_path / "h"), hint_lines(BANK), max_lines=100)
    assert r["failed"] == 3 and len(v.calls) == 3 and "in a row" in r["stopped"]


def test_speak_plays_a_kept_hint_without_a_call(tmp_path):
    hint = hint_lines(BANK)[0]
    HintVoices(tmp_path / "hint_voice").put(hint, b"RIFF-kept-hint")
    v = FakeVoice()
    c = TestClient(create_app(writer=FakeWriter(), data_dir=tmp_path, painter=None, bank=BANK, voice=v))
    r = c.post("/v1/speak", json={"installId": INSTALL, "text": f" {hint} "})
    assert r.status_code == 200 and r.content == b"RIFF-kept-hint" and v.calls == []
    # any other line still goes to the voice
    other = c.post("/v1/speak", json={"installId": INSTALL, "text": "안녕, 탐험대장! 오늘도 같이 가 보자."})
    assert other.status_code == 200 and len(v.calls) == 1


def test_a_kept_hint_plays_even_with_no_voice_at_all(tmp_path):
    hint = hint_lines(BANK)[0]
    HintVoices(tmp_path / "hint_voice").put(hint, b"RIFF-kept-hint")
    c = TestClient(create_app(writer=FakeWriter(), data_dir=tmp_path, painter=None, bank=BANK, voice=None))
    assert c.post("/v1/speak", json={"installId": INSTALL, "text": hint}).content == b"RIFF-kept-hint"
