"""Reads Lumi's lines aloud with Gemini's speech model, in a warm picture-book voice.

The app sends each line exactly as it will say it (story, question and its own instruction joined) to POST /v1/speak
and plays the WAV that comes back; it never uses the car's own TTS. A line is made once and kept (service.speak_line).

Two keys: the free key (GEMINI_API_KEY) is tried first, inside the free limits (Quota). When it is refused or fails,
the paid key (GEMINI_API_KEY_PAID) is tried once, with no limit of its own, and every switch to it is logged. With no
paid key, a failed line has no sound and the app shows it as a caption.
"""
import difflib
import hashlib
import io
import logging
import os
import re
import threading
import time
import wave
from datetime import date
from typing import Protocol

from .models import FRIEND_LABEL
from .pacing import Pace

log = logging.getLogger("voice")

TTS_MODEL = "gemini-3.8-flash-tts"
TTS_VOICE = "Leda"
# Gemini's speech model returns raw PCM: 24 kHz, mono, 16-bit
RATE = 24_000

STYLE = (
    "A warm, playful Korean storyteller reading a picture book aloud to a young child in the back seat. "
    "Never flat like a news reader: use a wide, lively pitch range and speak with a smile. "
    "Slow down a little just before a clue, lift the end of every question, say exclamations with excitement, "
    "and make sound words (like 딸깍, 모락모락, 투덜투덜) fun and bouncy. "
    "Pronounce every word clearly, at a pace a child can easily follow."
)
# the same words read plainly, to hear what the style adds (tools/tts_sample.py --plain)
PLAIN = "Read the text in a plain, neutral Korean voice."
# The speech model sometimes reads a made-up name wrong (반테 came out as 방태, 2026-11-27). Said with every style,
# also one set in TTS_STYLE, so it is never lost.
NAMES_GUIDE = (
    "Read every character name exactly as written, one syllable at a time, never blending it into the next sound: "
    "반테 (Ban-te, never 방테), 코냥 (Ko-nyang), 리아 (Ri-a), 투부기 (Tu-bu-gi), "
    "파삐 (Pa-ppi, a tense pp like the 빠 in 아빠, never 파피), 포또 (Po-tto), "
    "이오 (I-o), 쏠랑이 (Ssol-lang-i), 캐스핀 (Kae-seu-pin). "
    "Read an English word as the English word (on sounds like 온, three like 쓰리), never letter by letter. "
    "Read every word exactly as written: do not add, drop or change words or sentence endings."
)
NAMES = tuple(label for label, *_ in FRIEND_LABEL.values())
# A name the speech model says wrong whatever it is told, spelled for the speech model the way it comes out right; the
# subtitles keep the name as written, and a listener may write it either way. 파삐 came out with a soft 피, and 팝삐
# gives the tight 삐 (picked by ear, 2026-11-27).
RESPELL = {"파삐": "팝삐"}
# hearing a recording back: Gemini writes down what it hears, and every name in the line must be in it
# light and quick (about 2 s for a line); it heard 반테 read as 방테 where the full flash model did too
LISTEN_MODEL = "gemini-3.5-flash-lite"
LISTEN_ASK = ("이 한국어 음성을 들리는 그대로 한글로 받아써. 맞춤법을 고치거나 뜻을 짐작해서 바꾸지 말고, "
              "들린 소리 그대로 적어. 받아쓴 문장만 답해.")
# recordings made for one line on one key before giving up on that key
LISTEN_TRIES = 2


def full_style(style: str) -> str:
    return f"{style} {NAMES_GUIDE}"


def _plain(text: str) -> str:
    return re.sub(r"[^가-힣A-Za-z0-9]", "", text)


# vowels Korean speech no longer tells apart (ㅐ=ㅔ, ㅒ=ㅖ, ㅙ=ㅚ=ㅞ): a listener writes either, so they count as one;
# consonants and final consonants stay strict, so 방테 or 판테 for 반테 is still wrong
_SAME_VOWEL = {1: 5, 3: 7, 10: 15, 11: 15}
# a tense consonant and its aspirated pair are written either way too (파삐 came back as 파피, 2026-11-27):
# ㅋ=ㄲ, ㅌ=ㄸ, ㅍ=ㅃ, ㅊ=ㅉ. A plain consonant stays apart, so 판테 for 반테 is still wrong
_SAME_INITIAL = {15: 1, 16: 4, 17: 8, 14: 13}
# how much of a line must come back for the recording to count as read as written (whole line, after the above)
LINE_MATCH = 0.9


def _sounds(text: str) -> str:
    out = []
    for ch in _plain(text):
        code = ord(ch) - 0xAC00
        if 0 <= code < 11172:
            initial, vowel, final = code // 588, (code % 588) // 28, code % 28
            initial = _SAME_INITIAL.get(initial, initial)
            ch = chr(0xAC00 + initial * 588 + _SAME_VOWEL.get(vowel, vowel) * 28 + final)
        out.append(ch)
    return "".join(out)


def respelled(text: str) -> str:
    """[text] with every name in RESPELL as it goes to the speech model."""
    for written, said in RESPELL.items():
        text = text.replace(written, said)
    return text


def sayable(text: str) -> str:
    """The words as they go to the speech model. An English word in capitals (THREE, ON) is read letter by letter, so
    it goes in small letters, wherever the line came from (a question, a hint); a name in RESPELL goes respelled."""
    # a Hangul particle right after the word (THREE가) is no boundary for \b, so only Latin letters count around it
    return re.sub(r"(?<![A-Za-z])[A-Z]{2,}(?![A-Za-z])", lambda m: m.group().lower(), respelled(text))


def alike(text: str, heard: str) -> float:
    """How much of [text] came back in what was [heard], 0 to 1, as written or as respelled for the speech model.
    A listener writes 빼기 as - and 더하기 as +."""
    heard = _sounds(heard.replace("-", " 빼기 ").replace("+", " 더하기 "))
    return max(difflib.SequenceMatcher(None, _sounds(t), heard).ratio() for t in {text, respelled(text)})


def misheard(text: str, heard: str) -> list[str]:
    """The friend names in [text] that do not sound in what was [heard], as written or as respelled."""
    said = _sounds(heard)
    return [name for name in NAMES
            if name in _plain(text) and _sounds(name) not in said and _sounds(respelled(name)) not in said]


class VoiceError(Exception):
    pass


class MisreadError(VoiceError):
    """The recording says a name wrong, every time it was made."""


class QuotaError(VoiceError):
    """The free quota is used up (or the guard below says so); no call was made, or the model refused with 429."""


class Voice(Protocol):
    def speak(self, text: str) -> bytes:
        """WAV bytes of [text] read aloud."""


def configured_identity() -> str:
    """Model, voice and style as .env sets them: what a recording made now would sound like, key or no key."""
    model = os.environ.get("TTS_MODEL", TTS_MODEL)
    voice = os.environ.get("TTS_VOICE", TTS_VOICE)
    style = full_style(os.environ.get("TTS_STYLE") or STYLE)
    return f"{model}|{voice}|{style}"


def cache_key(voice: object | None, text: str) -> str:
    """Same words, same voice, same style: the same recording. [voice] names itself with ``identity``; with no voice
    the configured one is used, so a line made before is still found. A line with a name in RESPELL is keyed as it is
    said, so a recording made before the name was respelled is made again; every other line keeps its key."""
    who = configured_identity() if voice is None else getattr(voice, "identity", type(voice).__name__)
    return hashlib.sha256(f"{who}\n{respelled(text)}".encode("utf-8")).hexdigest()


class Quota:
    """Keeps the speech calls inside a free key's limits, so a key is never pushed past them.

    At most [per_day] calls a day (TTS_DAILY_MAX). When the model answers 429, the calls stop: until tomorrow for a
    daily limit, for [cooldown] seconds for a per-minute one. Every line that is not read is read by the car's voice.
    """

    def __init__(self, per_day: int, cooldown: float = 60.0, today=date.today, clock=time.monotonic):
        self.per_day = per_day
        self.cooldown = cooldown
        self._today = today
        self._clock = clock
        self._day = today()
        self._used = 0
        self._closed_day: date | None = None
        self._closed_until = 0.0
        self._lock = threading.Lock()

    def take(self) -> None:
        """Raises QuotaError instead of letting a call go out."""
        with self._lock:
            today = self._today()
            if today != self._day:
                self._day, self._used = today, 0
            if self._closed_day == today:
                raise QuotaError("daily free quota used up")
            if self._clock() < self._closed_until:
                raise QuotaError("per-minute quota, cooling down")
            if self._used >= self.per_day:
                raise QuotaError(f"TTS_DAILY_MAX={self.per_day} reached")
            self._used += 1

    def refused(self, error: Exception) -> None:
        """The model said 429: stop for the day when it names a daily limit, otherwise for a minute."""
        text = str(error)
        with self._lock:
            if "PerDay" in text or "per day" in text.lower():
                self._closed_day = self._today()
            else:
                self._closed_until = self._clock() + self.cooldown


def is_quota(error: Exception) -> bool:
    return getattr(error, "code", None) == 429 or "RESOURCE_EXHAUSTED" in str(error)


def to_wav(audio: bytes, rate: int = RATE) -> bytes:
    """[audio] as a WAV file: kept as it is when it already is one, raw 16-bit mono PCM gets a RIFF header."""
    if audio[:4] == b"RIFF" and audio[8:12] == b"WAVE":
        return audio
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(audio)
    return out.getvalue()


class GeminiVoice:
    """Shared by the threads that read one episode's lines at the same time, so the client is made once up front
    (as in GeminiPainter)."""

    def __init__(self, model: str | None = None, voice: str | None = None, style: str | None = None, client=None,
                 quota: Quota | None = None, api_key: str | None = None, limited: bool = True,
                 listener: "Listener | None" = None, pace: Pace | None = None):
        from google import genai
        from google.genai import types

        self.model = model or os.environ.get("TTS_MODEL", TTS_MODEL)
        self.voice = voice or os.environ.get("TTS_VOICE", TTS_VOICE)
        self.style = full_style(style or os.environ.get("TTS_STYLE") or STYLE)
        # hears each recording with a friend's name in it back, and makes it again when a name came out wrong
        self.listener = listener
        # the paid key's per-minute limit: a busy minute waits here instead of failing (app.pacing)
        self.pace = pace
        # a 429 is not tried again here: on a free key it means the quota is gone, and [Quota] stops the calls instead
        self.client = client or genai.Client(api_key=api_key, http_options=types.HttpOptions(
            timeout=120_000,
            retry_options=types.HttpRetryOptions(attempts=2, initial_delay=4, max_delay=20, http_status_codes=[500, 503]),
        ))
        # the paid key has no limit of its own (limited=False); TTS_DAILY_MAX is only for the free key
        # the free tier gives this model 10 calls a day (measured 2026-11-27), so the 11th goes to the paid key at once
        self.quota = (quota or Quota(per_day=int(os.environ.get("TTS_DAILY_MAX", "10")))) if limited else None

    @property
    def identity(self) -> str:
        """What makes two recordings of the same words differ, for the cache."""
        return f"{self.model}|{self.voice}|{self.style}"

    def request(self, text: str) -> dict:
        """The call's arguments. The line goes in ``text`` exactly as written and the reading instructions only in
        ``speech_metadata.style``: the model reads ``text`` verbatim, so an instruction there would be read aloud."""
        return dict(
            model=self.model,
            contents=[{"role": "user", "parts": [{"text": sayable(text), "speech_metadata": {"style": self.style}}]}],
            config={"response_modalities": ["AUDIO"], "speech_config": {"voice_config": {"voice": self.voice}}},
        )

    def speak(self, text: str) -> bytes:
        """Every recording is heard back. A misread friend name is never kept; a line whose words came back changed
        (an added or dropped word, another ending) is made again, and the closer of the recordings is kept."""
        wav = self._make(text)
        if self.listener is None:
            return wav
        best, best_score = None, -1.0
        wrong: list[str] = []
        for tried in range(1, LISTEN_TRIES + 1):
            heard = self.listener.heard(wav)
            if heard is None:
                # could not check (the listener failed or its quota is gone): keep the sound rather than lose it
                return wav if best is None else best
            wrong = misheard(text, heard)
            score = alike(text, heard)
            if not wrong:
                if score >= LINE_MATCH:
                    return wav
                if score > best_score:
                    best, best_score = wav, score
            log.warning("voice: %s, heard %r, try %d of %d",
                        f"name misread ({', '.join(wrong)})" if wrong else f"words changed ({score:.2f})",
                        heard[:80], tried, LISTEN_TRIES)
            if tried < LISTEN_TRIES:
                wav = self._make(text)
        if best is not None:
            # the names were right but some words came back changed every time: the closest recording beats none
            return best
        raise MisreadError(f"name misread: {', '.join(wrong)}")

    def _make(self, text: str) -> bytes:
        if self.quota is not None:
            self.quota.take()
        try:
            if self.pace is not None:
                response = self.pace.call(lambda: self.client.models.generate_content(**self.request(text)))
            else:
                response = self.client.models.generate_content(**self.request(text))
        except Exception as e:
            if is_quota(e) and self.quota is not None:
                self.quota.refused(e)
                raise QuotaError(str(e)) from e
            raise
        candidate = response.candidates[0] if response.candidates else None
        parts = (candidate.content.parts if candidate and candidate.content else None) or []
        for part in parts:
            if part.inline_data is not None and part.inline_data.data:
                return to_wav(part.inline_data.data)
        raise VoiceError(f"no audio in the reply (prompt_feedback={getattr(response, 'prompt_feedback', None)})")


class Listener:
    """Hears a recording back: Gemini writes down what it hears (on the free key when there is one, so checking
    costs nothing). Any failure means "could not check", never "wrong"."""

    def __init__(self, api_key: str | None = None, client=None, model: str | None = None,
                 paid_key: str | None = None, paid_client=None):
        from google import genai
        from google.genai import types

        self.model = model or os.environ.get("TTS_LISTEN_MODEL", LISTEN_MODEL)

        def made(key):
            # a busy minute (500, 503) passes in seconds; a 429 means the quota is gone and is not tried again
            return genai.Client(api_key=key, http_options=types.HttpOptions(
                timeout=60_000,
                retry_options=types.HttpRetryOptions(attempts=3, initial_delay=2, max_delay=10, http_status_codes=[500, 503]),
            ))

        # the free key first; when it is busy or used up, the paid key once (a few seconds of audio cost next to nothing)
        self.clients = [c for c in (client or (made(api_key) if api_key else None),
                                    paid_client or (made(paid_key) if paid_key else None)) if c is not None]

    def heard(self, wav: bytes) -> str | None:
        from google.genai import types
        for client in self.clients:
            try:
                response = client.models.generate_content(
                    model=self.model, contents=[LISTEN_ASK, types.Part.from_bytes(data=wav, mime_type="audio/wav")])
                return (response.text or "").strip() or None
            except Exception as e:
                log.warning("voice: listen check failed on one key (%s)", _why(e))
        log.warning("voice: listen check skipped")
        return None


def _why(e: Exception) -> str:
    """A short reason for the log: the error's kind and code, never the request (which carries no key, but stays out)."""
    code = getattr(e, "code", None)
    return f"{type(e).__name__}{f' {code}' if code else ''}"


class KeyedVoice:
    """The free key first, then the paid key once. [free] or [paid] may be missing (no such key in .env)."""

    def __init__(self, free: Voice | None, paid: Voice | None):
        if free is None and paid is None:
            raise ValueError("no voice")
        self.free = free
        self.paid = paid

    @property
    def identity(self) -> str:
        # both keys make the same recording (same model, voice and style), so they share the cache
        return getattr(self.free or self.paid, "identity", "voice")

    def speak(self, text: str) -> bytes:
        if self.free is None:
            log.warning("voice: no free key, using the paid key")
            return self.paid.speak(text)
        try:
            return self.free.speak(text)
        except Exception as e:
            if self.paid is None:
                raise
            log.warning("voice: free key %s (%s), retrying once with the paid key",
                        "quota" if isinstance(e, QuotaError) else "failed", _why(e))
        return self.paid.speak(text)


SPARE_MODEL = "gemini-3.8-flash-lite-tts"
# once the main model says its day is used up, it is asked again this long after (Google's day ends at midnight Pacific)
GATE_RETRY_SECONDS = 1800.0


def used_up_for_the_day(error: Exception) -> bool:
    """A 429 naming a daily limit, or the free key's own daily guard (Quota)."""
    text = str(error)
    return "PerDay" in text or "per day" in text.lower() or "daily" in text.lower() or "TTS_DAILY_MAX" in text


class Gated:
    """[voice] until its daily limit is reached, then closed: no call goes out (QuotaError at once) until [retry]
    seconds later, when one line tries it again. The server reads lines with the spare model meanwhile (main.speak)."""

    def __init__(self, voice: Voice, retry: float = GATE_RETRY_SECONDS, clock=time.monotonic):
        self.voice = voice
        self.retry = retry
        self._clock = clock
        self._closed_until = 0.0
        self._lock = threading.Lock()

    @property
    def identity(self) -> str:
        return getattr(self.voice, "identity", type(self.voice).__name__)

    @property
    def closed(self) -> bool:
        return self._clock() < self._closed_until

    def speak(self, text: str) -> bytes:
        if self.closed:
            raise QuotaError("main voice used up for the day, spare voice instead")
        try:
            return self.voice.speak(text)
        except Exception as e:
            if used_up_for_the_day(e):
                with self._lock:
                    self._closed_until = self._clock() + self.retry
                log.warning("voice: %s used up for the day (%s), spare voice for %.0f min",
                            self.identity.split("|")[0], _why(e), self.retry / 60)
            raise


def make_spare_voice() -> Voice | None:
    """The cheaper model read when the main one is used up for the day (TTS_SPARE_MODEL, off to have none)."""
    model = os.environ.get("TTS_SPARE_MODEL", SPARE_MODEL).strip()
    if not model or model.lower() == "off" or model == os.environ.get("TTS_MODEL", TTS_MODEL):
        return None
    return make_voice(model)


def make_voice(model: str | None = None) -> Voice | None:
    """GEMINI_API_KEY (free) and GEMINI_API_KEY_PAID; TTS_MODEL=off, or neither key, means no voice. [model] is the
    TTS_MODEL one unless given (the spare voice and the hint recordings name theirs)."""
    if os.environ.get("TTS_MODEL", "").lower() == "off":
        return None
    free_key = os.environ.get("GEMINI_API_KEY", "").strip()
    paid_key = os.environ.get("GEMINI_API_KEY_PAID", "").strip()
    # TTS_LISTEN=off skips hearing the recordings back
    listener = None
    if os.environ.get("TTS_LISTEN", "").lower() != "off" and (free_key or paid_key):
        listener = Listener(api_key=free_key or None, paid_key=paid_key or None)
    free = GeminiVoice(model=model, api_key=free_key, listener=listener) if free_key else None
    # Google counts each model's minute apart, so every model gets its own Pace
    paid = GeminiVoice(model=model, api_key=paid_key, limited=False, listener=listener,
                       pace=Pace(int(os.environ.get("TTS_PAID_RPM", "8")),
                                 f"speech (paid key{', ' + model if model else ''})")) if paid_key else None
    if free is None and paid is None:
        return None
    return free if paid is None else KeyedVoice(free, paid)
