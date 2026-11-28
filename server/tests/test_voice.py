"""Lumi's lines read aloud on POST /v1/speak: made once and kept, free key first, then the paid key once, and no
sound (a caption in the app) when neither works. No test calls a real model."""
import hashlib
import io
import logging
import wave
from datetime import date

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.voice import (NAMES_GUIDE, alike, cache_key, configured_identity, GeminiVoice, KeyedVoice, MisreadError, Quota,
                       QuotaError, make_voice, misheard, to_wav)
from tests.fakes import BANK, FakeWriter

INSTALL = "0123456789abcdef0123"
LINE = "수달 요리사가 큰일이래! 3 더하기 2만큼 바람을 세게 해 줘! 어떻게 하면 되는지 안전 지킴이에게 말해 줘!"


def pcm(text: str) -> bytes:
    return b"\x00\x01" * (10 + len(text))


class FakeVoice:
    def __init__(self, fail: Exception | None = None, identity: str = "fake|voice|style"):
        self.calls: list[str] = []
        self.fail = fail
        self.identity = identity

    def speak(self, text: str) -> bytes:
        self.calls.append(text)
        if self.fail is not None:
            raise self.fail
        return to_wav(pcm(text))


def client(tmp_path, voice):
    return TestClient(create_app(writer=FakeWriter(), data_dir=tmp_path, painter=None, bank=BANK, voice=voice))


def say(c, text=LINE, install=INSTALL):
    return c.post("/v1/speak", json={"installId": install, "text": text})


# ---------------------------------------------------------------- the endpoint and its cache


def test_a_line_is_read_aloud_and_kept(tmp_path):
    v = FakeVoice()
    c = client(tmp_path, v)
    r = say(c)
    assert r.status_code == 200 and r.headers["content-type"] == "audio/wav" and r.content[:4] == b"RIFF"
    # the same line again, even spaced differently, is not made again
    assert say(c, "  " + LINE.replace(" ", "  ") + " ").content == r.content
    assert v.calls == [LINE]
    assert c.get("/health").json()["voice"] is True


def test_the_cache_outlives_the_server_and_a_missing_key(tmp_path, monkeypatch):
    for name in ("TTS_MODEL", "TTS_VOICE", "TTS_STYLE"):
        monkeypatch.delenv(name, raising=False)
    made = GeminiVoice(client=_Obj(models=_Models(pcm(LINE))))
    assert say(client(tmp_path, made)).status_code == 200
    # a restart with no key at all: the line made before still plays; a new one has no sound
    c = client(tmp_path, None)
    assert say(c).status_code == 200
    assert say(c, "처음 듣는 말").status_code == 503
    assert c.get("/health").json()["voice"] is False


def test_another_voice_or_style_is_another_recording(tmp_path):
    first, second = FakeVoice(identity="m|A|calm"), FakeVoice(identity="m|B|calm")
    say(client(tmp_path, first))
    say(client(tmp_path, second))
    assert len(first.calls) == len(second.calls) == 1


def test_no_sound_is_503_and_bad_requests_are_refused(tmp_path):
    c = client(tmp_path, FakeVoice(fail=RuntimeError("refused")))
    assert say(c).status_code == 503
    # nothing is kept for a line that failed, so it is tried again next time
    assert not list(tmp_path.glob("audio_cache/*.wav"))
    assert say(c, "").status_code == 422
    assert say(c, "가" * 601).status_code == 422
    assert say(c, install="not an id!").status_code == 422


def test_speak_rejects_forbidden_or_non_korean_lines_and_cleans_up(tmp_path):
    import os
    from app import service
    from app.store import prune_audio

    v = FakeVoice()
    c = client(tmp_path, v)
    # English quiz words inside a Korean line pass; scary/unsafe/URL/non-Korean spam are rejected before touching voice
    assert say(c, "켜진 독서등이 모두 two개가 되게 해 줘!").status_code == 200
    for bad in ("귀신이 나타났어!", "창문을 열자!", "https://evil.example/ 가자", "Hello world only"):
        r = say(c, bad)
        assert r.status_code == 422 and r.json()["detail"]["error"] == "speak_rejected"
    assert len(v.calls) == 1
    # in-flight lock table never leaks finished keys
    assert service._making == {}

    cache_dir = tmp_path / "audio_prune"
    cache_dir.mkdir()
    old_wav = cache_dir / ("a" * 64 + ".wav")
    mid_wav = cache_dir / ("b" * 64 + ".wav")
    new_wav = cache_dir / ("c" * 64 + ".wav")
    for p, t in ((old_wav, 1000), (mid_wav, 90000), (new_wav, 95000)):
        p.write_bytes(b"RIFF")
        os.utime(p, (t, t))
    assert prune_audio(cache_dir, max_age_hours=24, max_files=1, now=100000) == 2
    assert not old_wav.exists() and not mid_wav.exists() and new_wav.exists()


def test_lines_a_day_are_limited(tmp_path, monkeypatch):
    monkeypatch.setenv("SPEAK_DAILY_PER_INSTALL", "2")
    c = client(tmp_path, FakeVoice())
    assert [say(c, f"줄 {i}").status_code for i in range(3)] == [200, 200, 429]


# ---------------------------------------------------------------- free key first, paid key once


def test_a_used_up_free_key_goes_to_the_paid_key_once_and_says_so(tmp_path, caplog):
    free, paid = FakeVoice(fail=QuotaError("429 RESOURCE_EXHAUSTED")), FakeVoice()
    c = client(tmp_path, KeyedVoice(free, paid))
    with caplog.at_level(logging.WARNING, logger="voice"):
        assert say(c).status_code == 200
    assert free.calls == [LINE] and paid.calls == [LINE]
    assert "paid key" in caplog.text
    # kept: the paid key is not asked again for the same line
    say(c)
    assert len(paid.calls) == 1


def test_any_free_key_error_goes_to_the_paid_key(tmp_path):
    paid = FakeVoice()
    assert say(client(tmp_path, KeyedVoice(FakeVoice(fail=RuntimeError("500")), paid))).status_code == 200
    assert paid.calls == [LINE]


def test_when_both_keys_fail_there_is_no_sound(tmp_path):
    paid = FakeVoice(fail=RuntimeError("down"))
    assert say(client(tmp_path, KeyedVoice(FakeVoice(fail=QuotaError("429")), paid))).status_code == 503
    assert paid.calls == [LINE]


def test_a_working_free_key_never_touches_the_paid_key(tmp_path, caplog):
    free, paid = FakeVoice(), FakeVoice()
    with caplog.at_level(logging.WARNING, logger="voice"):
        say(client(tmp_path, KeyedVoice(free, paid)))
    assert paid.calls == [] and "paid key" not in caplog.text


def test_the_paid_key_has_no_daily_limit():
    models = _Models(pcm("가"))
    paid = GeminiVoice(model="m", voice="v", style="s", client=_Obj(models=models), limited=False)
    assert paid.quota is None
    for _ in range(5):
        paid.speak("가")
    free = GeminiVoice(model="m", voice="v", style="s", client=_Obj(models=models), quota=Quota(per_day=1))
    free.speak("가")
    with pytest.raises(QuotaError):
        free.speak("가")


def test_make_voice_follows_the_keys(monkeypatch):
    import app.voice as voice

    class Made:
        def __init__(self, model=None, api_key=None, limited=True, listener=None, pace=None):
            self.model, self.pace = model, pace
            self.api_key, self.limited, self.listener = api_key, limited, listener

    monkeypatch.setattr(voice, "Listener", lambda api_key=None, paid_key=None: ("listener", api_key, paid_key))

    monkeypatch.setattr(voice, "GeminiVoice", Made)
    monkeypatch.delenv("TTS_MODEL", raising=False)
    assert make_voice() is None
    monkeypatch.setenv("GEMINI_API_KEY", "free")
    only_free = make_voice()
    assert isinstance(only_free, Made) and only_free.limited
    # an empty paid key changes nothing
    monkeypatch.setenv("GEMINI_API_KEY_PAID", " ")
    assert isinstance(make_voice(), Made)
    monkeypatch.setenv("GEMINI_API_KEY_PAID", "paid")
    both = make_voice()
    assert isinstance(both, KeyedVoice)
    assert (both.free.api_key, both.free.limited, both.paid.api_key, both.paid.limited) == ("free", True, "paid", False)
    # both keys hear their recordings back: through the free key first, then the paid key
    assert both.free.listener == both.paid.listener == ("listener", "free", "paid")
    monkeypatch.setenv("TTS_LISTEN", "off")
    assert make_voice().free.listener is None
    monkeypatch.setenv("TTS_MODEL", "off")
    assert make_voice() is None


# ---------------------------------------------------------------- the model call itself


def test_pcm_gets_a_header_and_wav_is_kept():
    raw = pcm("가나다")
    wav = to_wav(raw)
    with wave.open(io.BytesIO(wav)) as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, 24_000)
        assert w.readframes(w.getnframes()) == raw
    assert to_wav(wav) == wav


class _Obj:
    def __init__(self, **kw):
        self.__dict__.update(kw)


class _Models:
    def __init__(self, data: bytes):
        self.data = data
        self.sent = None

    def generate_content(self, **kw):
        self.sent = kw
        part = _Obj(inline_data=_Obj(data=self.data))
        return _Obj(candidates=[_Obj(content=_Obj(parts=[part]))], prompt_feedback=None)


def test_the_style_is_never_part_of_the_words(monkeypatch):
    style = "Read in Korean like a warm, playful storyteller reading a picture book to a 5-year-old."
    monkeypatch.setenv("TTS_STYLE", style)
    monkeypatch.setenv("TTS_VOICE", "ko-kr-storyteller-4")
    models = _Models(pcm(LINE))
    v = GeminiVoice(model="m", client=_Obj(models=models))
    assert v.speak(LINE)[:4] == b"RIFF"
    part = models.sent["contents"][0]["parts"][0]
    # a style from .env still carries how to say the friends' names
    assert part == {"text": LINE, "speech_metadata": {"style": f"{style} {NAMES_GUIDE}"}}
    assert "반테 (Ban-te, never 방테)" in part["speech_metadata"]["style"]
    assert models.sent["config"]["response_modalities"] == ["AUDIO"]
    assert models.sent["config"]["speech_config"]["voice_config"]["voice"] == "ko-kr-storyteller-4"


def test_the_quota_guard():
    now = [0.0]
    day = [date(2026, 11, 26)]
    q = Quota(per_day=2, cooldown=60, today=lambda: day[0], clock=lambda: now[0])
    q.take()
    q.take()
    with pytest.raises(QuotaError):
        q.take()
    day[0] = date(2026, 11, 27)
    q.take()
    # a per-minute 429 waits a minute; a daily one waits for tomorrow
    q.refused(Exception("429 RESOURCE_EXHAUSTED. GenerateRequestsPerMinutePerProjectPerModel-FreeTier"))
    with pytest.raises(QuotaError):
        q.take()
    now[0] += 61
    q.take()
    q.refused(Exception("429 RESOURCE_EXHAUSTED. GenerateRequestsPerDayPerProjectPerModel-FreeTier"))
    now[0] += 3600
    with pytest.raises(QuotaError):
        q.take()


class _Refusing:
    def __init__(self):
        self.calls = 0

    def generate_content(self, **kw):
        self.calls += 1
        e = Exception("429 RESOURCE_EXHAUSTED. Quota exceeded: GenerateRequestsPerDayPerProjectPerModel-FreeTier")
        e.code = 429
        raise e


def test_a_429_on_the_free_key_closes_it_without_more_calls():
    models = _Refusing()
    v = GeminiVoice(model="m", voice="v", style="S", client=_Obj(models=models), quota=Quota(per_day=100))
    for _ in range(3):
        with pytest.raises(QuotaError):
            v.speak("안녕")
    assert models.calls == 1


# ---------------------------------------------------------------- hearing a recording back


class _Ears:
    """Writes down, in turn, what each recording is heard as; None stands for a check that could not be made."""

    def __init__(self, *heard):
        self.heard_as = list(heard)
        self.calls = 0

    def heard(self, wav):
        self.calls += 1
        return self.heard_as.pop(0)


def _voice(ears, limited=False):
    models = _Models(pcm("반테"))
    calls = []
    original = models.generate_content

    def counted(**kw):
        calls.append(kw)
        return original(**kw)

    models.generate_content = counted
    return GeminiVoice(model="m", client=_Obj(models=models), listener=ears, limited=limited), calls


def test_a_misread_name_is_found():
    assert misheard("나는 반테야.", "나는 방태야.") == ["반테"]
    assert misheard("나는 반테야.", "나는 반 테야!") == []
    assert misheard("쏠랑이가 왔어", "쏠랑이가 왔어") == []
    assert misheard("바람을 세게 해 줘", "바람을 새게 해 줘") == []
    # ㅔ and ㅐ sound the same, so a listener writing 반태 heard it right; the final consonant and the first one do not
    assert misheard("나는 반테야", "나는 반태야") == []
    assert misheard("나는 반테야", "나는 판태야") == ["반테"]
    assert misheard("캐스핀이 왔어", "케스핀이 왔어") == []


def test_a_misread_name_is_made_again():
    ears = _Ears("안녕 나는 방태야", "안녕 나는 반테야")
    v, calls = _voice(ears)
    assert v.speak("안녕, 나는 반테야!")[:4] == b"RIFF"
    assert len(calls) == 2 and ears.calls == 2


def test_a_name_misread_every_time_gives_up_on_that_key():
    v, calls = _voice(_Ears("방태야", "방태야"))
    with pytest.raises(MisreadError):
        v.speak("나는 반테야")
    assert len(calls) == 2


def test_the_paid_key_takes_a_line_the_free_key_keeps_misreading(tmp_path):
    free, _ = _voice(_Ears("방태야", "방태야"))
    paid, paid_calls = _voice(_Ears("나는 반테야"))
    assert KeyedVoice(free, paid).speak("나는 반테야")[:4] == b"RIFF"
    assert len(paid_calls) == 1


def test_a_line_read_as_written_is_kept_and_a_failed_check_keeps_the_sound():
    ears = _Ears(LINE)
    v, calls = _voice(ears)
    v.speak(LINE)
    assert ears.calls == 1 and len(calls) == 1
    v, calls = _voice(_Ears(None))
    assert v.speak("나는 반테야")[:4] == b"RIFF"
    assert len(calls) == 1


def test_changed_words_are_made_again_and_the_closer_recording_is_kept():
    line = "온도를 3도 올렸으니 차 안이 곧 포근해질 거야."
    # an added word the first time, read as written the second time
    v, calls = _voice(_Ears("온도를 3도 올렸으니 잠시 후 곧 포근해질 거야.", "온도를 3도 올렸으니 차 안이 곧 포근해질 거야."))
    assert v.speak(line)[:4] == b"RIFF" and len(calls) == 2
    # changed both times: still a sound (never none for this), and no third try
    v, calls = _voice(_Ears("버클을 짚고 말했죠", "포크를 집고 말했죠"))
    assert v.speak("탐험대장, 버클을 손으로 짚고 딸깍 확인이라고 말해 줘.")[:4] == b"RIFF" and len(calls) == 2


def test_a_listener_writes_sounds_and_signs_its_own_way():
    # a tense consonant and its aspirated pair are written either way: 파피 for 파삐 is heard right
    assert misheard("나는 파삐야", "나는 파피야") == []
    assert misheard("나는 반테야", "나는 판테야") == ["반테"]
    # 빼기 and 더하기 written as - and +
    assert alike("11 빼기 6 빼기 2도만큼 올려 줘", "11 - 6 - 2도 만큼 올려줘") >= 0.9


def test_pappi_goes_to_the_speech_model_as_it_is_said():
    models = _Models(pcm("파삐"))
    v = GeminiVoice(model="m", voice="v", style="S", client=_Obj(models=models))
    v.speak("파삐야, THREE 버튼을 같이 찾자!")
    assert models.sent["contents"][0]["parts"][0]["text"] == "팝삐야, three 버튼을 같이 찾자!"
    # only a line with the name is recorded again: every other line keeps the recording it has
    old = lambda text: hashlib.sha256(f"{configured_identity()}\n{text}".encode("utf-8")).hexdigest()
    assert cache_key(None, "반테야, 같이 찾자!") == old("반테야, 같이 찾자!")
    assert cache_key(None, "파삐야, 같이 찾자!") != old("파삐야, 같이 찾자!")
    # a listener may write the name as written, as respelled, or with an aspirated ㅍ
    for heard in ("파삐야 같이 찾자", "팝삐야 같이 찾자", "파피야 같이 찾자"):
        assert misheard("파삐야, 같이 찾자!", heard) == []
        assert alike("파삐야, 같이 찾자!", heard) >= 0.9
    assert misheard("파삐야, 같이 찾자!", "바비야 같이 찾자") == ["파삐"]


def test_the_listener_tries_the_paid_key_when_the_free_key_cannot_hear():
    from app.voice import Listener

    class Busy:
        def generate_content(self, **kw):
            raise RuntimeError("503 busy")

    class Hears:
        def generate_content(self, **kw):
            return _Obj(text=" 나는 방태야 ")

    assert Listener(client=_Obj(models=Busy()), paid_client=_Obj(models=Hears())).heard(b"RIFF") == "나는 방태야"
    assert Listener(client=_Obj(models=Busy()), paid_client=_Obj(models=Busy())).heard(b"RIFF") is None


def test_english_in_capitals_is_sent_in_small_letters():
    models = _Models(pcm("x"))
    v = GeminiVoice(model="m", client=_Obj(models=models))
    v.speak("힌트: THREE가 우리말로 몇일까? ON의 글자 수만큼! 반테 OK")
    assert models.sent["contents"][0]["parts"][0]["text"] == "힌트: three가 우리말로 몇일까? on의 글자 수만큼! 반테 ok"
