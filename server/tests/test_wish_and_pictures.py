"""The family's story wish and the drawn pictures: what is refused, what is drawn, what is kept."""
import io
import json

from fastapi.testclient import TestClient
from PIL import Image

from app.checker import answer_words, art_problems, wish_problems
from app.main import create_app
from app.models import LESSON_LABEL, Art, EpisodeRequest
from app.prompt import SYSTEM, user_message
from app.rules import RULES
from tests.fakes import AC, BANK, ENGLISH, MATH, PATTERN, REASON, FakeWriter, draft, filled, without_situations

BODY = {
    "installId": "0123456789abcdef0123",
    "ages": ["CAPTAIN"],
    "minutes": 10,
    "context": {"timeOfDay": "AFTERNOON", "season": "AUTUMN"},
    "puzzles": [[MATH], [REASON], ["BELT"]],
    "friend": "BANTE",
}
KEYS = [MATH, REASON, "BELT"]
ART = Art(
    cover="공룡 섬에 도착한 반테가 활짝 웃는다",
    puzzles=["반테가 멈춘 바람개비 옆에서 사과 바구니를 든다", "반테가 모래 언덕 위에서 뿌연 유리를 바라본다", "탐험대장 자리 옆에서 반테가 손짓한다"],
)


class FakePainter:
    """Every picture it is asked for goes to ``calls``."""

    def __init__(self, fail: set[str] = frozenset()):
        self.calls: list[str] = []
        self.fail = fail

    def paint(self, description: str) -> bytes:
        self.calls.append(description)
        if any(f in description for f in self.fail):
            raise RuntimeError("refused")
        out = io.BytesIO()
        Image.new("RGB", (30, 20), (255, 197, 49)).save(out, "WEBP")
        return out.getvalue()


def client(tmp_path, writer, painter=None, bank=BANK):
    return TestClient(create_app(writer=writer, data_dir=tmp_path, painter=painter, bank=bank))


def test_a_scary_or_personal_wish_is_refused_before_any_model(tmp_path):
    w = FakeWriter()
    for bad_wish in ("귀신 나오는 집 탐험", "귀.신 나오는 집", "창 문 열 어 서 별 보기", "전 화 번 호 맞히기"):
        r = client(tmp_path, w).post("/v1/episode", json={**BODY, "wish": bad_wish})
        assert r.status_code == 422 and r.json()["detail"]["error"] == "wish_rejected"
    assert w.calls == 0
    assert wish_problems("우리 집 주소 알려 줘")
    assert wish_problems("귀\u200b신 놀이")
    assert not wish_problems("T-rex 공룡 섬에서 보물찾기")  # English words are fine in a wish
    # self-harm, written plainly or broken up
    for bad_wish in ("자살", "자 살 놀이", "자.살", "자해하는 친구", "극단적인 선택", "목매기", "투신"):
        assert wish_problems(bad_wish), bad_wish
    assert not wish_problems("모자 사러 가기") and not wish_problems("모자 살래") and not wish_problems("잣나무 숲 구조대")


def test_the_wish_reaches_the_writer_and_too_long_is_refused(tmp_path):
    req = EpisodeRequest.model_validate({**BODY, "wish": "  공룡 섬에서   보물찾기 "})
    assert req.wish == "공룡 섬에서 보물찾기"
    assert "가족이 바란 이야기: «공룡 섬에서 보물찾기»" in user_message(req)
    assert "가족이 바란 이야기: «" not in user_message(EpisodeRequest.model_validate(BODY))
    assert client(tmp_path, FakeWriter()).post("/v1/episode", json={**BODY, "wish": "가" * 61}).status_code == 422


def test_the_lesson_reaches_the_writer_with_its_easy_words(tmp_path):
    req = EpisodeRequest.model_validate({**BODY, "wish": "공룡 섬에서 펼쳐지는 구조대 이야기", "lesson": "HONESTY"})
    assert "오늘의 교훈: 정직 (풀어 쓴 말: 솔직하게 말하는 마음" in user_message(req)
    assert "오늘의 교훈:" not in user_message(EpisodeRequest.model_validate(BODY))
    # the writer is told how to carry any lesson in any kind of story, the same for every episode
    assert all(rule in SYSTEM for rule in ("친구의 행동으로 보여 준다", "설교하듯 말하지 않는다", "솔직하게 말하고 다 같이 고친다",
                                           "풀어 쓴 말"))
    assert len(LESSON_LABEL) == 10
    assert client(tmp_path, FakeWriter()).post("/v1/episode", json={**BODY, "lesson": "BRAVERY"}).status_code == 422


def test_answer_words_are_the_answer_number():
    assert answer_words([MATH]) == ["5", "다섯"]
    assert answer_words(["Q:PATTERN:LIGHTS:1"]) == ["1", "하나"]
    # one-letter number words (두, 세, 네) are in too many ordinary words to keep out of a picture
    assert answer_words([ENGLISH]) == ["2"] and answer_words([PATTERN]) == ["4"]
    # a situation has no number to give away
    assert answer_words([AC]) == [] and answer_words([REASON]) == []
    assert set(answer_words(["BELT"])) == {"풀어", "풀고", "풀면", "풀러"}


def test_a_picture_that_would_show_the_answer_is_not_drawn():
    req = EpisodeRequest.model_validate(BODY)
    for leak, word in (("사과 다섯 개를 든 반테", "다섯"), ("사과 5개를 든 반테", "5")):
        leaky = ART.model_copy(update={"puzzles": [leak, *ART.puzzles[1:]]})
        problems = art_problems(filled(KEYS, art=leaky), req)
        assert list(problems) == ["p1"] and f"정답이 보이는 말: {word}" in problems["p1"]
    assert art_problems(filled(KEYS, art=ART), req) == {}


def test_pictures_are_drawn_served_and_tied_to_their_scenes(tmp_path):
    painter = FakePainter()
    c = client(tmp_path, FakeWriter(draft(KEYS, art=ART, world="공룡 섬")), painter)
    ep = c.post("/v1/episode", json={**BODY, "wish": "공룡 섬에서 보물찾기", "pictures": True}).json()
    assert set(ep["images"]) == {"cover", "p1", "p2", "p3"} and len(painter.calls) == 4
    assert ep["world"] == "공룡 섬"
    assert ep["pictures"] == {MATH: "p1", REASON: "p2", "BELT": "p3"}
    assert ep["scenes"][MATH]["picture"] == "p1" and ep["scenes"][REASON]["picture"] == "p2"
    assert ep["scenes"]["BELT"]["picture"] == "p3"
    got = c.get(ep["images"]["cover"])
    assert got.status_code == 200 and got.headers["content-type"] == "image/webp"
    assert c.get(f"/v1/images/{ep['storyId']}/../../etc.webp").status_code == 404
    assert c.get(f"/v1/images/{ep['storyId']}/p9.webp").status_code == 404
    # the wish itself is never written down
    assert "보물찾기" not in (tmp_path / "episodes.jsonl").read_text(encoding="utf-8")


def test_a_dropped_scene_still_gets_its_puzzle_picture(tmp_path):
    painter = FakePainter()
    # the bank has no situation, so the car tells its own over the drawn picture
    writer = FakeWriter(draft(KEYS, art=ART), draft(KEYS, art=ART), draft([REASON], art=ART))
    ep = client(tmp_path, writer, painter, bank=without_situations()).post("/v1/episode", json={**BODY, "pictures": True}).json()
    assert ep["dropped"] == [REASON] and REASON not in ep["scenes"]
    assert ep["pictures"][REASON] == "p2" and "p2" in ep["images"]


def test_the_painter_is_told_what_a_rule_puzzle_picture_must_not_show(tmp_path, monkeypatch):
    # only the belt mission still has a word table; give it something its picture must not show
    monkeypatch.setitem(RULES.scene, "BELT", RULES.scene["BELT"].model_copy(update={"art_never": ["버클"]}))
    painter = FakePainter()
    client(tmp_path, FakeWriter(draft(KEYS, art=ART)), painter).post("/v1/episode", json={**BODY, "pictures": True})
    belt = [c for c in painter.calls if c.startswith(ART.puzzles[2])]
    assert belt and "그림에 절대 넣지 말 것: 버클" in belt[0]
    # a quiz picture shows no answer by its description alone (art_problems), so it carries no such note
    assert not any("그림에 절대" in c for c in painter.calls if not c.startswith(ART.puzzles[2]))


class BeltPainter(FakePainter):
    """Also draws the belt mission's child seat: the picture before the click, then the same picture buckled."""

    def __init__(self, fail_buckle: bool = False):
        super().__init__()
        self.belt: list[str] = []
        self.buckled: list[bytes] = []
        self.fail_buckle = fail_buckle

    def paint_belt(self, description: str) -> bytes:
        self.belt.append(description)
        out = io.BytesIO()
        Image.new("RGB", (30, 20), (90, 120, 180)).save(out, "WEBP")
        return out.getvalue()

    def buckle(self, before: bytes) -> bytes:
        self.buckled.append(before)
        if self.fail_buckle:
            raise RuntimeError("refused")
        return before + b"ok"


def test_the_belt_mission_is_drawn_before_and_after_the_click(tmp_path):
    painter = BeltPainter()
    c = client(tmp_path, FakeWriter(draft(KEYS, art=ART)), painter)
    ep = c.post("/v1/episode", json={**BODY, "pictures": True}).json()
    assert set(ep["images"]) == {"cover", "p1", "p2", "p3", "p3_ok"}
    # the belt scene goes to the child seat, the other pictures are drawn as before
    assert len(painter.belt) == 1 and painter.belt[0].startswith(ART.puzzles[2]) and len(painter.calls) == 3
    # the picture after is edited from the very picture before, and only the scene before is tied to the puzzle
    before = (tmp_path / "images" / ep["storyId"] / "p3.webp").read_bytes()
    assert painter.buckled == [before]
    assert (tmp_path / "images" / ep["storyId"] / "p3_ok.webp").read_bytes() == before + b"ok"
    assert ep["scenes"]["BELT"]["picture"] == "p3" and ep["pictures"]["BELT"] == "p3"
    assert c.get(ep["images"]["p3_ok"]).status_code == 200
    assert c.get(f"/v1/images/{ep['storyId']}/p3_no.webp").status_code == 404
    # the writer describes the friend in the seat and leaves the belt to the painter
    assert "탐험대 카시트" in SYSTEM


def test_a_failed_click_picture_keeps_the_one_before(tmp_path):
    painter = BeltPainter(fail_buckle=True)
    ep = client(tmp_path, FakeWriter(draft(KEYS, art=ART)), painter).post("/v1/episode", json={**BODY, "pictures": True}).json()
    assert "p3" in ep["images"] and "p3_ok" not in ep["images"]
    assert ep["scenes"]["BELT"]["picture"] == "p3"


def test_a_failed_picture_is_left_to_the_app(tmp_path):
    painter = FakePainter(fail={"모래 언덕"})
    ep = client(tmp_path, FakeWriter(draft(KEYS, art=ART)), painter).post("/v1/episode", json={**BODY, "pictures": True}).json()
    assert "p2" not in ep["images"] and ep["scenes"][REASON]["picture"] is None
    assert ep["scenes"][MATH]["picture"] == "p1"


def test_no_scene_pictures_unless_asked(tmp_path):
    painter = FakePainter()
    ep = client(tmp_path, FakeWriter(draft(KEYS, art=ART)), painter).post("/v1/episode", json=BODY).json()
    assert ep["images"] == {} and painter.calls == []
    record = json.loads((tmp_path / "episodes.jsonl").read_text(encoding="utf-8"))
    assert record["wish"] is False


def test_expired_episode_pictures_are_pruned(tmp_path):
    import os
    from app.store import prune_images

    images_dir = tmp_path / "images"
    old_dir = images_dir / ("a" * 32)
    fresh_dir = images_dir / ("b" * 32)
    old_dir.mkdir(parents=True)
    fresh_dir.mkdir(parents=True)
    (old_dir / "cover.webp").write_bytes(b"x")
    (fresh_dir / "cover.webp").write_bytes(b"x")
    os.utime(old_dir, (1000, 1000))
    os.utime(fresh_dir, (95000, 95000))

    assert prune_images(images_dir, max_age_hours=24, now=100000) == 1
    assert not old_dir.exists() and fresh_dir.exists()

