"""The story shelf: checked stories kept for other cars to reuse.

A story is shelved in a folder named by its cell (friend, youngest age, minutes, and the wish when there was one):
``library/{friend}/{youngest}/{minutes}m[/w-{wish digest}]/{storyId}/``. It holds ``draft.json`` (the writer's draft and
the request it answered, without the install id and context), its pictures as ``img/{name}.webp``, and ``story.json``
(what the car gets, with its meta). ``story.json`` is written last, so a folder without it is never indexed.
Its lines read aloud are kept next to it as ``voice/{line key}.wav`` (the key of the voice cache), so a story played
again weeks later needs no new recording; unlike the voice cache, the shelf is never pruned.

No install id, IP or time of day is stored. The season is kept, and a story is offered only in its own season. The
wish is kept, because it is what a wished story is found by:
it is offered only to a car that sends exactly the same wish, so it tells no family anything it did not type itself.
Which stories a car has already played is kept on the car (HeardStories), never here.

Storage is one bucket (S3) or a local folder (dev server, tests). The index of what is shelved lives in memory and is
read back from the storage when the server starts.
"""
import json
import logging
import os
import random
import threading
import time
from concurrent.futures import ThreadPoolExecutor, wait
from datetime import date, datetime, timezone
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, get_args

from pydantic import BaseModel

from . import checker, quiz
from .bank import Bank
from .checker import result_problems, timeless_problems
from .store import _digest
from .models import Age, Draft, Episode, EpisodeRequest, FriendCode, Lesson, LibraryRequest, Season, Theme

log = logging.getLogger("story")
ROOT = "library/"
BLOCKED = "blocked/"
# the install id put back into a stored request when it is read: a shelved story belongs to no install
SHELF_INSTALL = "0" * 16
# a placeholder only, put back where the request needs a context: the checks never read it
SHELF_CONTEXT = {"timeOfDay": "AFTERNOON", "season": "SPRING"}
DEFAULT_MODELS = "claude"
LOAD_WORKERS = 16
DEFAULT_LOAD_SECONDS = 60.0
# a wished cell offers at least this many: a car that has played some of them still finds one it has not
WISH_OFFER = 30
# a line read aloud belongs to a story when it carries a piece of the story's text at least this long
VOICE_PART_MIN = 10
# the most recordings kept with one story (an episode is a few dozen lines)
VOICE_PER_STORY = 80
# a story just made is watched this long for its lines read aloud before it is shelved; then forgotten
EXPECT_SECONDS = 30 * 60
EXPECT_MAX = 50


class Storage(Protocol):
    def put(self, path: str, data: bytes, content_type: str) -> None: ...

    def get(self, path: str) -> bytes | None:
        """None when there is no such object."""

    def list(self, prefix: str) -> list[str]:
        """Every object path under prefix, at any depth."""


class FolderStorage:
    """Storage on a local folder."""

    def __init__(self, root: Path):
        self.root = Path(root)

    def put(self, path: str, data: bytes, content_type: str) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    def get(self, path: str) -> bytes | None:
        target = self.root / path
        return target.read_bytes() if target.is_file() else None

    def list(self, prefix: str) -> list[str]:
        return sorted(p.relative_to(self.root).as_posix() for p in self.root.rglob("*")
                      if p.is_file() and p.relative_to(self.root).as_posix().startswith(prefix))


class S3Storage:
    """Storage on one bucket, every path under ``prefix``."""

    def __init__(self, bucket: str, prefix: str = "", client=None):
        self.bucket = bucket
        self.prefix = prefix.strip("/") + "/" if prefix.strip("/") else ""
        if client is None:
            import boto3
            from botocore.config import Config
            client = boto3.client("s3", config=Config(connect_timeout=3, read_timeout=10, retries={"max_attempts": 2}))
        self.client = client

    def put(self, path: str, data: bytes, content_type: str) -> None:
        self.client.put_object(Bucket=self.bucket, Key=self.prefix + path, Body=data, ContentType=content_type)

    def get(self, path: str) -> bytes | None:
        from botocore.exceptions import ClientError
        try:
            return self.client.get_object(Bucket=self.bucket, Key=self.prefix + path)["Body"].read()
        except ClientError as e:
            if e.response.get("Error", {}).get("Code") in ("NoSuchKey", "404"):
                return None
            raise

    def list(self, prefix: str) -> list[str]:
        out = []
        for page in self.client.get_paginator("list_objects_v2").paginate(Bucket=self.bucket, Prefix=self.prefix + prefix):
            out += [o["Key"][len(self.prefix):] for o in page.get("Contents", [])]
        return out


def wish_key(wish: str | None) -> str:
    """The folder name part of a wish: a digest, so the stored paths carry no free text."""
    return _digest(wish) if wish else ""


@dataclass(frozen=True)
class Cell:
    friend: str
    youngest: str
    minutes: int
    # wish_key() of the family's wish; empty for the stories written without one
    wish: str = ""

    def path(self) -> str:
        base = f"library/{self.friend}/{self.youngest}/{self.minutes}m"
        return f"{base}/w-{self.wish}" if self.wish else base


class ShelfMeta(BaseModel):
    ages: list[Age]
    minutes: int
    friend: FriendCode
    lesson: Lesson | None
    theme: Theme
    # the writer model as the episode names it
    writer: str
    # ISO date (UTC), e.g. "2026-11-26"
    made: str
    # Bank.digest() when shelved
    bank: str
    # the family's wish the story was written for (EpisodeRequest.wish); None when there was none
    wish: str | None = None
    # the season it was written in, and offered in; None for a story written without one (offered in any)
    season: Season | None = None


class Shelved(BaseModel):
    """story.json."""

    meta: ShelfMeta
    # the request's slots, alternates included
    puzzles: list[list[str]]
    # exactly what the car gets; its pictures are kept next to it (img/) and served by /v1/images
    episode: Episode

    @property
    def cell(self) -> Cell:
        youngest = min(self.meta.ages, key=["LITTLE", "CAPTAIN", "DETECTIVE"].index)
        return Cell(self.meta.friend, youngest, self.meta.minutes, wish_key(self.meta.wish))


Check = Callable[[Shelved, Draft, EpisodeRequest], list[str]]


class Library:
    def __init__(self, storage: Storage, models: tuple[str, ...] = (), cap: int = 200, min_fresh: int = 30,
                 fresh: float = 0.3, offer: int = 10, blocks_per_ip: int = 5, load_seconds: float = DEFAULT_LOAD_SECONDS,
                 run: Callable[[Callable[[], None]], object] | None = None):
        self.storage = storage
        self.models = models
        self.load_seconds = load_seconds
        self.cap, self.min_fresh, self.fresh, self.offer, self.blocks_per_ip = cap, min_fresh, fresh, offer, blocks_per_ip
        self._pool = None if run else ThreadPoolExecutor(max_workers=1)
        self.run = run or self._pool.submit
        # request threads and the shelving job both touch the index
        self.lock = threading.Lock()
        # the check ``load`` was given: a story is held to it before it is shelved too, so none is shelved that the
        # next start would drop
        self.check: Check | None = None
        self.stories: dict[str, Shelved] = {}
        self.cells: dict[Cell, list[str]] = {}
        # stories nobody is offered any more (filled in when a story is blocked)
        self.blocked: set[str] = set()
        # reports that took a story off the shelf today, per hashed address
        self._block_day = date.today()
        self._blocks: dict[str, int] = {}
        # the pieces of each shelved story's text a line read aloud may carry
        self.parts: dict[str, frozenset[str]] = {}
        # recordings kept on the shelf: line key -> storage path, and the keys each story keeps
        self.voices: dict[str, str] = {}
        self.story_voices: dict[str, set[str]] = {}
        # stories just made that may soon be shelved: id -> (deadline, text pieces, keys of the lines read so far)
        self.expected: dict[str, tuple[float, frozenset[str], set[str]]] = {}
        # where a line made before its story was shelved is read from (the voice cache, set by the server)
        self.recording: Callable[[str], bytes | None] = lambda key: None

    def drain(self, timeout: float) -> None:
        """Waits until the default worker has finished the jobs it was given so far (for tests); nothing to wait for
        when ``run`` was injected."""
        if self._pool is not None:
            self._pool.submit(lambda: None).result(timeout)

    def allows(self, model: str) -> bool:
        return not self.models or model.startswith(self.models)

    def _index(self, shelved: Shelved) -> None:
        with self.lock:
            sid = shelved.episode.storyId
            if sid not in self.stories:
                self.cells.setdefault(shelved.cell, []).append(sid)
            self.stories[sid] = shelved
            self.parts[sid] = spoken_parts(shelved.episode)

    def add(self, shelved: Shelved, draft: Draft, request: EpisodeRequest,
            pictures: dict[str, bytes] | None = None) -> None:
        base = f"{shelved.cell.path()}/{shelved.episode.storyId}"
        # the draft and the pictures first and story.json last: a half-written folder is never indexed
        # a wish is kept only when there was one
        left_out = {"installId", "context"} | ({"wish"} if request.wish is None else set())
        draft_body = {"draft": draft.model_dump(), "request": request.model_dump(exclude=left_out)}
        self.storage.put(f"{base}/draft.json", json.dumps(draft_body, ensure_ascii=False).encode("utf-8"),
                         "application/json")
        for name, data in (pictures or {}).items():
            self.storage.put(f"{base}/img/{name}.webp", data, "image/webp")
        self.storage.put(f"{base}/story.json", shelved.model_dump_json().encode("utf-8"), "application/json")
        self._index(shelved)
        # the lines read aloud while the story was being drawn and checked
        with self.lock:
            early = self.expected.pop(shelved.episode.storyId, (0.0, frozenset(), set()))[2]
        for key in sorted(early):
            if (data := self.recording(key)) is not None:
                self._put_voice(shelved.episode.storyId, key, data)

    # ---------------------------------------------------------------- lines read aloud

    def expect(self, ep: Episode, now: float | None = None) -> None:
        """Watches a story just made for its lines read aloud, so the ones read before it is shelved are kept too."""
        now = time.monotonic() if now is None else now
        with self.lock:
            for sid in [s for s, (deadline, _, _) in self.expected.items() if deadline < now]:
                del self.expected[sid]
            if len(self.expected) >= EXPECT_MAX:
                del self.expected[min(self.expected, key=lambda s: self.expected[s][0])]
            self.expected[ep.storyId] = (now + EXPECT_SECONDS, spoken_parts(ep), set())

    def voice(self, key: str) -> bytes | None:
        """A line's recording kept on the shelf, by its voice cache key; None when no shelved story keeps it."""
        with self.lock:
            path = self.voices.get(key)
        if path is None:
            return None
        try:
            return self.storage.get(path)
        except Exception:
            log.exception("shelf: the recording %s cannot be read", path)
            return None

    def keep_voice(self, text: str, key: str, data: bytes) -> None:
        """Keeps a line just read aloud with every shelved story it belongs to (after the response), and remembers it
        for the stories made a moment ago that are not shelved yet."""
        try:
            line = " ".join(text.split())
            with self.lock:
                shelved = [sid for sid, parts in self.parts.items() if sid not in self.blocked
                           and key not in self.story_voices.get(sid, ()) and _carries(line, parts)]
                for sid, (_, parts, keys) in self.expected.items():
                    if sid not in self.stories and len(keys) < VOICE_PER_STORY and _carries(line, parts):
                        keys.add(key)
            for sid in shelved:
                self.run(lambda sid=sid: self._put_voice(sid, key, data))
        except Exception:
            # the car already has its line: keeping it is a bonus
            log.exception("shelf: a line could not be kept")

    def _put_voice(self, story_id: str, key: str, data: bytes) -> None:
        with self.lock:
            shelved = self.stories.get(story_id)
            kept = self.story_voices.setdefault(story_id, set())
            if shelved is None or key in kept or len(kept) >= VOICE_PER_STORY:
                return
            kept.add(key)
        path = f"{shelved.cell.path()}/{story_id}/voice/{key}.wav"
        try:
            self.storage.put(path, data, "audio/wav")
        except Exception:
            with self.lock:
                kept.discard(key)
            log.exception("shelf: the recording %s cannot be kept", path)
            return
        with self.lock:
            self.voices.setdefault(key, path)

    def shelve_later(self, ep: Episode, draft: Draft, req: EpisodeRequest, bank_tag: str,
                     images: Path | None = None) -> bool:
        """Queues putting a story on the shelf after the response, with the pictures drawn for it (read from
        ``images``/{storyId}/, where they are kept for a day only). ``images`` is None when no pictures were asked
        for; when they were and not one is there, the story is not shelved, so later cars do not get it without
        pictures. False when it is skipped up front: its writer is not allowed, a scene was dropped, it names a time
        of day, or its cell is full."""
        try:
            if not self.allows(ep.model) or ep.dropped or timeless_problems(ep):
                return False
            shelved = Shelved(
                meta=ShelfMeta(ages=req.ages, minutes=req.minutes, friend=req.friend, lesson=req.lesson,
                               theme=draft.theme, writer=ep.model,
                               made=datetime.now(timezone.utc).date().isoformat(), bank=bank_tag, wish=req.wish,
                               season=req.context.season),
                puzzles=req.puzzles, episode=ep)
            if self.size(shelved.cell) >= self.cap:
                return False

            def job() -> None:
                try:
                    if self.check is not None:
                        # the request as ``load`` will rebuild it from what is stored
                        stored = EpisodeRequest(installId=SHELF_INSTALL, context=SHELF_CONTEXT,
                                                **req.model_dump(exclude={"installId", "context"}))
                        if problems := self.check(shelved, draft, stored):
                            log.warning("shelf: %s fails its check, not shelved: %s", ep.storyId, problems)
                            return
                    pictures = _read_pictures(ep, images)
                    if images is not None and not pictures:
                        log.warning("shelf: %s has no picture, not shelved", ep.storyId)
                        return
                    kept = shelved.model_copy(update={"episode": _only_pictures(ep, set(pictures))})
                    self.add(kept, draft, req, pictures)
                except Exception:
                    log.exception("shelf: %s could not be shelved", ep.storyId)

            self.run(job)
            return True
        except Exception:
            # the family already has its story: a shelf failure must never reach them
            log.exception("shelf: %s could not be queued", ep.storyId)
            return False

    def get(self, story_id: str) -> Shelved | None:
        with self.lock:
            return self.stories.get(story_id)

    def size(self, cell: Cell) -> int:
        with self.lock:
            return sum(1 for sid in self.cells.get(cell, ()) if sid not in self.blocked)

    def candidates(self, req: LibraryRequest, rnd: random.Random | None = None) -> list[Shelved]:
        """Up to ``offer`` shelved stories this car can play, chosen at random; none at all when a small cell asks the
        car to have a new story written. A wished cell never holds back: the family waits for nothing when a story for
        exactly their choices is there (the car skips the ones it has played and asks for a new one after the last),
        and it offers more, since a car may have played several of a few stories."""
        rnd = rnd or random.Random()
        cell = Cell(req.friend, req.youngest, req.minutes, wish_key(req.wish))
        playable = set(req.playable) | {"BELT"}
        ages = sorted(req.ages)
        with self.lock:
            fits = [s for sid in self.cells.get(cell, ()) if sid not in self.blocked
                    for s in [self.stories[sid]]
                    if sorted(s.meta.ages) == ages and s.meta.wish == req.wish and self.allows(s.meta.writer)
                    and _same_season(s.meta.season, req.season)
                    and all(playable.intersection(slot) for slot in s.puzzles)]
            size = sum(1 for sid in self.cells.get(cell, ()) if sid not in self.blocked)
        if req.wish is None and req.allowFresh and size < self.min_fresh and rnd.random() < self.fresh:
            return []
        offer = max(self.offer, WISH_OFFER) if req.wish else self.offer
        return rnd.sample(fits, min(offer, len(fits)))

    def fresh_lesson(self, req: EpisodeRequest, rnd: random.Random | None = None) -> EpisodeRequest:
        """The request with a lesson no story on its shelf carries yet (same friend, ages, minutes, wish and season), so a new
        story for the same choices is a new one; as it was when its lesson is new there, or every lesson is taken."""
        cell = Cell(req.friend, req.youngest, req.minutes, wish_key(req.wish))
        ages = sorted(req.ages)
        with self.lock:
            taken = {s.meta.lesson for sid in self.cells.get(cell, ()) if sid not in self.blocked
                     for s in [self.stories[sid]] if sorted(s.meta.ages) == ages and s.meta.wish == req.wish
                     and _same_season(s.meta.season, req.context.season)}
        if req.lesson not in taken:
            return req
        free = [lesson for lesson in get_args(Lesson) if lesson not in taken]
        return req.model_copy(update={"lesson": (rnd or random).choice(free)}) if free else req

    def picture(self, story_id: str, name: str) -> bytes | None:
        """A shelved story's picture, for /v1/images once the day-long copy is gone; None when there is none."""
        with self.lock:
            shelved = self.stories.get(story_id)
        if shelved is None or name not in shelved.episode.images:
            return None
        return self.storage.get(f"{shelved.cell.path()}/{story_id}/img/{name}.webp")

    def block(self, story_id: str, reason: str, ip: str, today: date | None = None) -> bool:
        """Takes a reported story off the shelf for good (its files stay). False when it is not on the shelf or this
        address has already taken ``blocks_per_ip`` stories off today."""
        today = today or date.today()
        key = _digest(ip)
        with self.lock:
            if today != self._block_day:
                self._block_day = today
                self._blocks.clear()
            if story_id not in self.stories:
                return False
            if story_id in self.blocked:
                return True
            if self._blocks.get(key, 0) >= self.blocks_per_ip:
                return False
            self._blocks[key] = self._blocks.get(key, 0) + 1
            self.blocked.add(story_id)
        try:
            body = {"reason": reason, "at": datetime.now(timezone.utc).isoformat()}
            self.storage.put(f"{BLOCKED}{story_id}.json", json.dumps(body).encode("utf-8"), "application/json")
        except Exception:
            # blocked in memory for now; a restart would offer it again until the next report
            log.exception("shelf: the block of %s could not be saved", story_id)
        return True

    def load(self, check: Check | None = None) -> None:
        """Index every complete story in the storage; a broken, half-written, unallowed or failing one is skipped.

        The blocked marks are read first, then the stories are fetched by a pool of workers. When ``load_seconds``
        have passed the server stops waiting and starts with the stories indexed so far; the blocked ids are known
        by then, so a reported story is never offered either way."""
        self.check = check
        blocked = [p.removeprefix(BLOCKED).removesuffix(".json") for p in self.storage.list(BLOCKED) if p.endswith(".json")]
        with self.lock:
            self.blocked.update(blocked)
        everything = self.storage.list(ROOT)
        paths = [p for p in everything if p.endswith("/story.json")]
        stop = threading.Event()
        pool = ThreadPoolExecutor(max_workers=LOAD_WORKERS)
        try:
            futures = [pool.submit(self._load_one, p, check, stop) for p in paths]
            done, pending = wait(futures, timeout=self.load_seconds)
            if pending:
                stop.set()
                with self.lock:
                    indexed = len(self.stories)
                log.warning("shelf: loading took more than %s s, starting with %d of %d stories", self.load_seconds,
                            indexed, len(paths))
        finally:
            pool.shutdown(wait=False, cancel_futures=True)
        # the recordings of the stories indexed: .../{storyId}/voice/{key}.wav
        with self.lock:
            for p in everything:
                parts = p.split("/")
                if len(parts) >= 3 and parts[-2] == "voice" and p.endswith(".wav") and parts[-3] in self.stories:
                    key = parts[-1].removesuffix(".wav")
                    self.story_voices.setdefault(parts[-3], set()).add(key)
                    self.voices.setdefault(key, p)

    def _load_one(self, path: str, check: Check | None, stop: threading.Event) -> None:
        if stop.is_set():
            return
        try:
            shelved = Shelved.model_validate_json(self.storage.get(path) or b"")
            raw = self.storage.get(path.removesuffix("story.json") + "draft.json")
            if raw is None:
                log.warning("shelf: %s has no draft.json, skipped", path)
                return
            body = json.loads(raw)
            draft = Draft.model_validate(body["draft"])
            request = EpisodeRequest(installId=SHELF_INSTALL, context=SHELF_CONTEXT, **body["request"])
            with self.lock:
                reported = shelved.episode.storyId in self.blocked
            if reported:
                log.info("shelf: %s was reported, skipped", path)
                return
            if not self.allows(shelved.meta.writer) or not self.allows(shelved.episode.model):
                log.info("shelf: %s was written by %s, skipped", path, shelved.meta.writer)
                return
            if check and (problems := check(shelved, draft, request)):
                log.warning("shelf: %s fails its check, skipped: %s", path, problems)
                return
        except Exception as e:
            log.warning("shelf: %s cannot be read or checked, skipped: %s", path, e)
            return
        if not stop.is_set():
            self._index(shelved)


def spoken_parts(ep: Episode) -> frozenset[str]:
    """Every piece of the story's own text (spaces collapsed) long enough not to turn up in another line by chance:
    a line the app reads aloud is one of them, or one of them with the app's own words around it."""
    out: set[str] = set()

    def walk(value: object) -> None:
        if isinstance(value, str):
            piece = " ".join(value.split())
            if len(piece) >= VOICE_PART_MIN:
                out.add(piece)
        elif isinstance(value, dict):
            for v in value.values():
                walk(v)
        elif isinstance(value, list):
            for v in value:
                walk(v)

    walk(ep.model_dump(exclude={"storyId", "model", "images", "pictures", "dropped"}))
    return frozenset(out)


def _carries(line: str, parts: frozenset[str]) -> bool:
    """The line read aloud holds one of the story's pieces, or is a long enough part of one."""
    return any(p in line for p in parts) or (len(line) >= VOICE_PART_MIN and any(line in p for p in parts))


def _same_season(shelved: str | None, asked: str | None) -> bool:
    """A story written without a season suits any, and a car that does not say its season takes any."""
    return shelved is None or asked is None or shelved == asked


def _read_pictures(ep: Episode, folder: Path | None) -> dict[str, bytes]:
    """The pictures drawn for ``ep`` that are still in ``folder``/{storyId}/, by name."""
    if folder is None:
        return {}
    out = {}
    for name in ep.images:
        path = folder / ep.storyId / f"{name}.webp"
        if path.is_file():
            out[name] = path.read_bytes()
    return out


def _only_pictures(ep: Episode, names: set[str]) -> Episode:
    """``ep`` naming only the pictures in ``names``: a scene whose picture is gone plays the app's own there."""
    return ep.model_copy(update={
        "images": {n: path for n, path in ep.images.items() if n in names},
        "pictures": {k: n for k, n in ep.pictures.items() if n in names},
        "scenes": {k: sc if sc.picture is None or sc.picture in names else sc.model_copy(update={"picture": None})
                   for k, sc in ep.scenes.items()}})


def recheck(shelved: Shelved, draft: Draft, request: EpisodeRequest, bank: Bank) -> list[str]:
    """Every problem the rules of today find in a shelved story: the checks of a new story, the lines said after an
    answer, a question the bank no longer holds, and season or time words. The request's context is never read."""
    verdict = checker.check(draft, request)
    problems = list(verdict.whole) + [p for ps in verdict.items.values() for p in ps]
    # a wish the server would refuse today: the story stays in storage but is never offered
    if request.wish:
        problems += [f"소원: {p}" for p in checker.wish_problems(request.wish)]
    for scene in draft.scenes:
        problems += result_problems(scene, request)
    for key, scene in shelved.episode.scenes.items():
        q = quiz.parse(key)
        if q is not None and scene.question is not None and not bank.has_text(q, request.youngest, scene.question.text, scene.hint):
            problems.append(f"문제가 문제 은행에 없음: {key}")
    return problems + timeless_problems(shelved.episode)


def _int(name: str, default: int) -> int:
    return int(os.environ.get(name) or default)


def make_library(check: Check | None = None) -> Library | None:
    """The shelf the environment asks for; None when it is off or cannot be loaded (the server then runs without)."""
    try:
        if bucket := os.environ.get("STORY_LIBRARY_BUCKET"):
            storage: Storage = S3Storage(bucket, os.environ.get("STORY_LIBRARY_PREFIX", ""))
        elif folder := os.environ.get("STORY_LIBRARY_DIR"):
            storage = FolderStorage(Path(folder))
        else:
            return None
        models = tuple(m.strip() for m in os.environ.get("STORY_LIBRARY_MODELS", DEFAULT_MODELS).split(",") if m.strip())
        lib = Library(storage, models=models, cap=_int("STORY_LIBRARY_CAP", 200), min_fresh=_int("STORY_LIBRARY_MIN", 30),
                      fresh=float(os.environ.get("STORY_LIBRARY_FRESH") or 0.3),
                      offer=_int("STORY_LIBRARY_CANDIDATES", 10), blocks_per_ip=_int("STORY_LIBRARY_BLOCKS_PER_IP", 5),
                      load_seconds=float(os.environ.get("STORY_LIBRARY_LOAD_SECONDS") or DEFAULT_LOAD_SECONDS))
        lib.load(check)
        return lib
    except Exception:
        log.exception("the story shelf cannot be loaded: the server runs without it")
        return None
