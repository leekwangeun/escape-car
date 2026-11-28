"""차량탈출 AI 이야기 서버.

POST /v1/episode                    the car sends this episode's answer keys (and maybe the family's story wish)
                                    and gets checked story lines back, with pictures when it asked for them
GET  /v1/images/{storyId}/{name}    one of those pictures (WebP); 202 while it is still being drawn
POST /v1/library                    the car asks the shelf for stories it can play (for its choices and wish); it
                                    picks one it has not played, and asks /v1/episode for a new one otherwise
POST /v1/speak                      one of Lumi's lines read aloud (WAV), made once and kept
POST /v1/report                     a parent reports a story (이야기 신고)
GET  /health
"""
import logging
import os
import re
import threading
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse

from .bank import Bank, load as load_bank
from .hint_voice import HintVoices
from .checker import speak_problems, wish_problems
from .library import Library, make_library, recheck
from .models import Episode, EpisodeRequest, LibraryAnswer, LibraryRequest, Report, Shelf, SpeakRequest
from .painter import Painter, make_painter
from .pictures import PENDING, Drawing
from .service import make_episode, speak_line, tidy_line, timeless_request
from .store import DailyLimit, IpLimit, ReportLimit, Store, prune_audio, prune_images
from .voice import Voice, cache_key, make_voice
from .writer import Writer, make_writer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("story")

_UNSET = object()

# the pictures of the episodes being drawn after their response, a few episodes at a time
_drawers = ThreadPoolExecutor(max_workers=4, thread_name_prefix="pictures")


def _run_pictures(job: Callable[[], None]) -> object:
    """Where an episode's drawing runs (tests put it on their own thread)."""
    return _drawers.submit(job)


def _read(path: Path) -> bytes | None:
    try:
        return path.read_bytes() if path.is_file() else None
    except OSError:
        return None


def client_ip(request: Request, trust_proxy: bool) -> str:
    """Real client address behind a trusted cloud reverse proxy, or the direct socket address otherwise."""
    if trust_proxy:
        forwarded = request.headers.get("x-forwarded-for", "")
        if forwarded:
            first = forwarded.split(",")[0].strip()
            if first:
                return first
        real_ip = request.headers.get("x-real-ip", "").strip()
        if real_ip:
            return real_ip
    return request.client.host if request.client else "unknown"


def create_app(
    writer: Writer | None = None,
    data_dir: Path | None = None,
    painter: Painter | None = _UNSET,
    bank: Bank | None = None,
    voice: Voice | None = _UNSET,
    library: Library | None = _UNSET,
) -> FastAPI:
    app = FastAPI(title="차량탈출 이야기 서버", docs_url=None, redoc_url=None)
    writer = writer or make_writer()
    painter = make_painter() if painter is _UNSET else painter
    # the questions made once and checked (tools/make_bank.py); a broken bank stops the server here
    bank = bank or load_bank()
    voice = make_voice() if voice is _UNSET else voice
    if library is _UNSET:
        # the environment decides: no STORY_LIBRARY_BUCKET or STORY_LIBRARY_DIR means no shelf
        library = make_library(lambda s, d, r: recheck(s, d, r, bank))
    app.state.library = library
    folder = data_dir or Path(os.environ.get("STORY_DATA_DIR", "data"))
    store = Store(folder)
    images = folder / "images"
    image_ttl_hours = float(os.environ.get("STORY_IMAGE_TTL_HOURS", "24"))
    trust_proxy = os.environ.get("STORY_TRUST_PROXY", "").lower() in ("1", "true", "yes")
    # recordings by words, voice and style, shared by every story and every car
    audio_cache = folder / "audio_cache"
    audio_ttl_hours = float(os.environ.get("STORY_AUDIO_TTL_HOURS", "72"))
    audio_max_files = int(os.environ.get("STORY_AUDIO_MAX_FILES", "2000"))
    # the bank's hints, recorded once and kept for good (app.hint_voice)
    hints = HintVoices(folder / "hint_voice")
    if library is not None:
        # a line read before its story was shelved is copied to the shelf from here
        library.recording = lambda key: _read(audio_cache / f"{key}.wav")
    limit = DailyLimit(
        per_install=int(os.environ.get("STORY_DAILY_PER_INSTALL", "20")),
        per_ip=int(os.environ.get("STORY_DAILY_PER_IP", "200")),
        per_day=int(os.environ.get("STORY_DAILY_TOTAL", "300")),
        state_file=folder / "daily_limit.json",
    )
    # lines read aloud a day: an episode is a few dozen; the paid key has no limit of its own, so this one guards it
    speak_limit = DailyLimit(
        per_install=int(os.environ.get("SPEAK_DAILY_PER_INSTALL", "400")),
        per_ip=int(os.environ.get("SPEAK_DAILY_PER_IP", "2000")),
        per_day=int(os.environ.get("SPEAK_DAILY_TOTAL", "20000")),
        state_file=folder / "speak_limit.json",
    )
    report_limit = ReportLimit(
        per_ip=int(os.environ.get("STORY_REPORT_PER_IP", "30")),
        per_day=int(os.environ.get("STORY_REPORT_DAILY_TOTAL", "500")),
    )
    library_limit = IpLimit(per_ip=int(os.environ.get("STORY_LIBRARY_PER_IP", "500")))
    # episodes whose pictures are still being drawn, by story id, so /v1/images can say "not yet"
    drawings: dict[str, Drawing] = {}
    drawings_lock = threading.Lock()
    prune_images(images, image_ttl_hours)
    prune_audio(audio_cache, audio_ttl_hours, audio_max_files)
    log.info("writer=%s painter=%s voice=%s trust_proxy=%s", type(writer).__name__,
             type(painter).__name__ if painter else "none", type(voice).__name__ if voice else "none", trust_proxy)

    @app.get("/health")
    def health() -> dict:
        return {"ok": True, "pictures": painter is not None, "voice": voice is not None}

    @app.post("/v1/episode", response_model=Episode)
    def episode(req: EpisodeRequest, request: Request) -> Episode:
        if req.wish and (problems := wish_problems(req.wish)):
            # the parent sees why and can write the wish another way; nothing is sent to a model
            raise HTTPException(status_code=422, detail={"error": "wish_rejected", "reasons": problems})
        ip = client_ip(request, trust_proxy)
        if not limit.take(req.installId, ip):
            raise HTTPException(status_code=429, detail="daily_limit")
        # with a shelf every story is written for it: without the season or time of day, so it suits any drive.
        # Without a wish the theme is picked here (the writer would pick it by the season); a wish picks its own
        timeless = library is not None
        if timeless and req.wish is None:
            req = timeless_request(req)
        if library is not None:
            # a new story for choices the shelf already has stories for carries a lesson they do not
            req = library.fresh_lesson(req)
        made = make_episode(req, writer, bank, timeless=timeless)
        if made is None:
            # the car plays its own reviewed lines
            raise HTTPException(status_code=502, detail="story_rejected")
        ep, draft = made
        if library is not None:
            # its lines read aloud before it is shelved are kept with it too
            library.expect(ep)
        if painter is None or not req.pictures:
            store.episode(ep, req)
            if library is not None:
                library.shelve_later(ep, draft, req, bank.digest())
            return ep
        prune_images(images, image_ttl_hours)
        drawing = Drawing(ep, draft, req, painter, images)
        with drawings_lock:
            drawings[ep.storyId] = drawing

        def drawn(d: Drawing) -> None:
            with drawings_lock:
                drawings.pop(d.story_id, None)
            if library is None:
                return
            # a story goes on the shelf only with every picture drawn: one that failed would show the car's own
            # picture to every family that gets it from the shelf
            if d.failed:
                log.info("shelf: %s is not shelved, pictures failed: %s", d.story_id, d.failed)
                return
            library.shelve_later(d.episode(), draft, req, bank.digest(), images)

        # the cover first: the episode is sent once it is in, and the other pictures follow
        ep = drawing.start(_run_pictures, drawn)
        store.episode(ep, req)
        return ep

    @app.post("/v1/library", response_model=LibraryAnswer)
    def shelf_candidates(req: LibraryRequest, request: Request) -> LibraryAnswer:
        # no record, no story limit and no log of the request: it carries only a cell and the playable keys
        if not library_limit.take(client_ip(request, trust_proxy)):
            raise HTTPException(status_code=429, detail="library_limit")
        if library is None:
            return LibraryAnswer(stories=[])
        return LibraryAnswer(stories=[Shelf(puzzles=s.puzzles, episode=s.episode) for s in library.candidates(req)])

    @app.get("/v1/images/{story_id}/{name}.webp")
    def image(story_id: str, name: str) -> Response:
        if not re.fullmatch(r"[0-9a-f]{32}", story_id) or not re.fullmatch(r"cover|ending|p[1-6](_ok)?", name):
            raise HTTPException(status_code=404)
        path = images / story_id / f"{name}.webp"
        if path.is_file():
            return FileResponse(path, media_type="image/webp")
        with drawings_lock:
            drawing = drawings.get(story_id)
        if drawing is not None and drawing.state(name) == PENDING:
            # still being drawn: the car asks again in a moment
            return Response(status_code=202, headers={"Retry-After": "3"})
        # the day-long copy is gone: a shelved story keeps its own
        data = library.picture(story_id, name) if library is not None else None
        if data is None:
            raise HTTPException(status_code=404)
        return Response(content=data, media_type="image/webp")

    @app.post("/v1/speak")
    def speak(req: SpeakRequest, request: Request) -> Response:
        if problems := speak_problems(req.text):
            raise HTTPException(status_code=422, detail={"error": "speak_rejected", "reasons": problems})
        ip = client_ip(request, trust_proxy)
        if not speak_limit.take(req.installId, ip):
            raise HTTPException(status_code=429, detail="daily_limit")
        # a bank hint recorded ahead of time (python -m app.hint_voice): no call, whatever the day's limit
        if (data := hints.get(req.text)) is not None:
            return Response(content=data, media_type="audio/wav")
        key = cache_key(voice, tidy_line(req.text))
        # a shelved story keeps its lines for good: no new recording, even long after the cache has dropped it
        data = library.voice(key) if library is not None else None
        if data is None:
            data = speak_line(req.text, voice, audio_cache)
        if data is None:
            # no sound for this line: the app shows it as a caption
            raise HTTPException(status_code=503, detail="no_voice")
        if library is not None:
            library.keep_voice(req.text, key, data)
        prune_audio(audio_cache, audio_ttl_hours, audio_max_files)
        return Response(content=data, media_type="audio/wav")

    @app.post("/v1/report", status_code=204)
    def report(r: Report, request: Request) -> Response:
        ip = client_ip(request, trust_proxy)
        allowed, should_write = report_limit.allow(ip, r.storyId, r.reason)
        if not allowed:
            raise HTTPException(status_code=429, detail="report_limit")
        if should_write:
            store.report(r)
        if library is not None:
            library.block(r.storyId, r.reason, ip)
        return Response(status_code=204)

    return app


def __getattr__(name: str):
    # the served app (uvicorn app.main:app) is made on first use, so importing create_app reads no question bank
    if name == "app":
        made = globals()["app"] = create_app()
        return made
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
