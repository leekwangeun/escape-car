"""Reports take a shelved story off the shelf; the shelf is checked again with today's rules when the server starts."""
import json
from datetime import date

from app.library import FolderStorage, Library, recheck
from app.models import LibraryRequest
from tests.fakes import BANK, MATH, filled, without_situations
from tests.test_library_api import ALT, STORY_ID, body, client, draft_and_request, stocked, story

IP = "10.0.0.1"


def report(c, sid, **kw):
    return c.post("/v1/report", json={"storyId": sid, "reason": "SCARY"}, **kw)


def ids(n):
    return [f"{i:032x}" for i in range(1, n + 1)]


def offered_ids(lib):
    return {s.episode.storyId for s in lib.candidates(LibraryRequest(**body()))}


def test_a_reported_shelf_story_is_no_longer_offered(tmp_path):
    lib = stocked(tmp_path, story())
    assert report(client(tmp_path, lib), STORY_ID).status_code == 204
    assert lib.candidates(LibraryRequest(**body())) == []
    assert lib.size(story().cell) == 0


def test_a_block_survives_a_restart(tmp_path):
    lib = stocked(tmp_path, story(), story("b" * 32))
    assert lib.block(STORY_ID, "SCARY", IP)
    marker = json.loads((tmp_path / "shelf" / "blocked" / f"{STORY_ID}.json").read_text(encoding="utf-8"))
    assert marker["reason"] == "SCARY" and marker["at"]
    again = Library(FolderStorage(tmp_path / "shelf"), run=lambda job: job())
    again.load()
    assert offered_ids(again) == {"b" * 32}
    assert list((tmp_path / "shelf" / "library").rglob(f"{STORY_ID}/story.json"))


def test_one_address_can_block_at_most_five_a_day(tmp_path):
    lib = stocked(tmp_path, *[story(i) for i in ids(6)])
    c = client(tmp_path, lib)
    for i in ids(6):
        assert report(c, i, headers={}).status_code == 204
    assert len(offered_ids(lib)) == 1
    assert len((tmp_path / "reports.jsonl").read_text(encoding="utf-8").splitlines()) == 6


def test_the_count_starts_over_the_next_day(tmp_path):
    lib = stocked(tmp_path, *[story(i) for i in ids(3)], blocks_per_ip=1)
    a, b, c = ids(3)
    assert lib.block(a, "OTHER", IP, today=date(2026, 11, 26))
    assert not lib.block(b, "OTHER", IP, today=date(2026, 11, 26))
    assert lib.block(b, "OTHER", IP, today=date(2026, 11, 27))
    assert lib.block(c, "OTHER", "10.0.0.2", today=date(2026, 11, 27))


def test_a_report_for_a_story_not_on_the_shelf_uses_no_block(tmp_path):
    lib = stocked(tmp_path, *[story(i) for i in ids(5)])
    c = client(tmp_path, lib)
    assert report(c, "f" * 32).status_code == 204
    for i in ids(5):
        report(c, i)
    assert offered_ids(lib) == set()


def request_for(s):
    # the slots the draft was written for (the story's own slots carry an alternate the draft has no scene for)
    d, r = draft_and_request(s)
    return d, r.model_copy(update={"puzzles": [[MATH], ["Q:REASON:DEFROST:1"], ["BELT"]]})


def edited_start(tmp_path, edit):
    """Shelves a story, lets edit(path) change its draft.json, and starts a library that rechecks."""
    s = story(puzzles=[[MATH], ["Q:REASON:DEFROST:1"], ["BELT"]])
    lib = stocked(tmp_path, s)
    path = next((tmp_path / "shelf" / "library").rglob("draft.json"))
    data = json.loads(path.read_text(encoding="utf-8"))
    edit(data)
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    started = Library(FolderStorage(tmp_path / "shelf"), run=lambda job: job())
    started.load(lambda s, d, r: recheck(s, d, r, BANK))
    return started


def test_a_story_the_rules_now_refuse_is_left_out_at_start(tmp_path):
    lib = edited_start(tmp_path, lambda d: d["draft"].update(intro=d["draft"]["intro"] + " 출발!"))
    assert lib.get(STORY_ID) is None


def test_a_result_line_the_rules_now_refuse_leaves_the_story_out(tmp_path):
    def edit(d):
        d["draft"]["scenes"][0]["result"] = "시동을 걸자 바람이 솔솔 나와!"
    assert edited_start(tmp_path, edit).get(STORY_ID) is None


def test_a_question_no_longer_in_the_bank_leaves_the_story_out(tmp_path):
    s = story()
    d, r = request_for(s)
    assert recheck(s, d, r, without_situations())
    assert any("문제" in p for p in recheck(s, d, r, without_situations()))


def test_time_words_found_at_start_leave_the_story_out(tmp_path):
    s = story()
    s.episode.intro += " 별이 뜬 밤이야."
    d, r = request_for(s)
    assert recheck(s, d, r, BANK)


def test_season_words_are_kept_on_the_shelf(tmp_path):
    s = story()
    s.episode.intro += " 따뜻한 봄날이야."
    d, r = request_for(s)
    assert recheck(s, d, r, BANK) == []


def test_a_clean_story_passes_the_recheck(tmp_path):
    s = story()
    d, r = request_for(s)
    assert recheck(s, d, r, BANK) == []
    started = edited_start(tmp_path, lambda d: None)
    assert started.get(STORY_ID) is not None


def test_a_hint_that_is_not_the_banks_leaves_the_story_out(tmp_path):
    s = story()
    key = next(k for k, sc in s.episode.scenes.items() if sc.question is not None)
    s.episode.scenes[key].hint = "다른 힌트를 쓰는 이야기."
    d, r = request_for(s)
    assert any("문제" in p for p in recheck(s, d, r, BANK))


def test_a_story_whose_wish_is_refused_today_leaves_the_story_out(tmp_path):
    s = story()
    d, r = request_for(s)
    assert recheck(s, d, r.model_copy(update={"wish": "북극 얼음 마을"}), BANK) == []
    assert any(p.startswith("소원: 자해나 자살") for p in recheck(s, d, r.model_copy(update={"wish": "자살"}), BANK))
