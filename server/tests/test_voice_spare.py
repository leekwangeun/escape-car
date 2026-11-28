"""The main voice (Flash) until its day is used up, then the spare one (Lite); the main one is tried again a while
later and, once open, reads every new line again."""
from fastapi.testclient import TestClient

from app.main import create_app
from app.voice import Gated, QuotaError
from tests.fakes import BANK, FakeWriter
from tests.test_voice import INSTALL, FakeVoice

LINE = "안녕, 탐험대장! 오늘은 바닷속 마을에 가 보자."
OTHER = "고쳤다! 우리 탐험대장 최고야!"


class DailyLimit(Exception):
    code = 429

    def __str__(self) -> str:
        return "429 RESOURCE_EXHAUSTED: GenerateRequestsPerDayPerProjectPerModel"


class MinuteLimit(Exception):
    code = 429

    def __str__(self) -> str:
        return "429 RESOURCE_EXHAUSTED: GenerateRequestsPerMinutePerProjectPerModel"


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def app(tmp_path, main, spare):
    return TestClient(create_app(writer=FakeWriter(), data_dir=tmp_path, painter=None, bank=BANK, voice=main,
                                 library=None, spare_voice=spare))


def say(c, text=LINE):
    return c.post("/v1/speak", json={"installId": INSTALL, "text": text})


def test_the_spare_voice_reads_once_the_main_one_is_used_up_for_the_day(tmp_path):
    flash = FakeVoice(fail=DailyLimit(), identity="flash|v|s")
    lite = FakeVoice(identity="lite|v|s")
    c = app(tmp_path, flash, lite)
    r = say(c)
    assert r.status_code == 200 and flash.calls == [LINE] and lite.calls == [LINE]
    # closed for now: the next line goes straight to the spare voice, with no call to the main one
    assert say(c, OTHER).status_code == 200
    assert flash.calls == [LINE] and lite.calls == [LINE, OTHER]
    # a line the spare voice made is played again without a call
    assert say(c).status_code == 200 and lite.calls == [LINE, OTHER]


def test_the_main_voice_is_tried_again_later_and_reads_new_lines_first(tmp_path):
    clock = Clock()
    flash = FakeVoice(fail=DailyLimit(), identity="flash|v|s")
    lite = FakeVoice(identity="lite|v|s")
    c = app(tmp_path, Gated(flash, retry=1800, clock=clock), lite)
    say(c)
    clock.now += 1799
    say(c, OTHER)
    assert flash.calls == [LINE]  # still closed
    clock.now += 2
    flash.fail = None  # Google's day turned
    r = say(c)
    # open again: the line the spare voice read before is read with the main one now, and kept apart
    assert r.status_code == 200 and flash.calls == [LINE, LINE] and lite.calls == [LINE, OTHER]
    assert say(c).status_code == 200 and flash.calls == [LINE, LINE]


def test_a_per_minute_limit_does_not_close_the_main_voice(tmp_path):
    flash = FakeVoice(fail=MinuteLimit(), identity="flash|v|s")
    lite = FakeVoice(identity="lite|v|s")
    c = app(tmp_path, flash, lite)
    say(c)
    say(c, OTHER)
    # the spare voice covers each line, but the main one is still asked every time
    assert flash.calls == [LINE, OTHER] and lite.calls == [LINE, OTHER]


def test_the_free_keys_daily_guard_closes_it_too():
    clock = Clock()
    g = Gated(FakeVoice(fail=QuotaError("TTS_DAILY_MAX=10 reached")), clock=clock)
    for _ in range(2):
        try:
            g.speak(LINE)
        except QuotaError:
            pass
    assert g.closed and len(g.voice.calls) == 1


def test_without_a_spare_voice_a_used_up_main_voice_still_means_no_sound(tmp_path):
    c = app(tmp_path, FakeVoice(fail=DailyLimit()), None)
    assert say(c).status_code == 503


def test_when_both_fail_the_line_has_no_sound(tmp_path):
    c = app(tmp_path, FakeVoice(fail=DailyLimit(), identity="flash|v|s"),
            FakeVoice(fail=DailyLimit(), identity="lite|v|s"))
    assert say(c).status_code == 503
