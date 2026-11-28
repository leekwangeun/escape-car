"""The question bank: every entry passes the same checks a written question gets and is about its own device, a
situation keeps to what the app can stand behind, a broken bank does not load, and an episode draws a question of the
key's device and answer with its hint."""
import json
import random
import re
from collections import Counter
from dataclasses import replace
from types import SimpleNamespace

import pytest

from app import bank, quiz
from app.bank import Bank, Entry
from tests.fakes import AC, BANK, COLD, COOL, ENTRIES, FOG, MATH, REASON, RUN, SUM, TIMES, UNCOVERED, WORD
from tools.make_bank import (DIAL_LABEL, PER_VALUE, REASON_DISTRACTORS, REASON_SECTION, REASONS, blind_reason, cell,
                             dedupe, entry_from, markdown, reason_choices, request_lines, targets)

# one more of each number kind the fixture bank leaves out: a letter count and a run by three (both for the oldest only)
LETTERS = Entry(id="fan-d-003", kind="ENGLISH", dial="FAN", age="DETECTIVE", answer=6, word="BANANA",
                text="banana, 바나나를 영어로 쓰면 몇 글자일까? 그 수만큼 바람 세기를 맞춰 줘!",
                # the question's own word may be in its hint
                hint="BANANA를 한 글자씩 짚으며 세어 봐.")
STEPS = Entry(id="fan-d-002", kind="PATTERN", dial="FAN", age="DETECTIVE", answer=8, terms=(17, 14, 11),
              text="바람 세기를 17, 14, 11 다음에 오는 수의 단으로 맞춰 줘!", hint="수가 얼마씩 작아지는지 봐.")
# situations the 4~6 are never told (quiz.REASON_NOT_LITTLE), from the shipped bank
HOT = Entry(id="temp-down-c-r02", kind="REASON", dial="TEMP_DOWN", age="CAPTAIN", answer=1,
            text="나는 땀을 닦고 있어, 에어컨이 켜져 있는데도 아직 조금 덥거든. 바람이 세지면 줄 세워 둔 도미노가 쓰러지니까 "
                 "알맞은 차량 버튼을 찾아 나를 도와줘!",
            hint="바람은 그대로 두고, 덥고 추운 정도를 정하는 숫자를 찾아서 시원한 쪽으로 바꿔 봐.")
POLLEN = Entry(id="recirc-on-c-r02", kind="REASON", dial="RECIRC", age="CAPTAIN", answer=1,
               text="바람 구멍 앞에 앉으니 고약한 냄새에 에취 재채기가 나와. "
                    "냄새는 차 밖에서 오는 것 같으니 창문은 닫아 둔 채로 알맞은 차량 버튼을 찾아 나를 도와줘!",
               hint="꽃가루는 밖에서 바람을 타고 들어와, 자동차 그림 안에서 화살표가 빙글 도는 버튼을 찾아봐.")
# the same kind and answer on three devices
FAN_3 = Entry(id="fan-l-003", kind="MATH", dial="FAN", age="LITTLE", answer=3, expr="1+2",
              text="1 더하기 2는 얼마일까? 그 수만큼 바람 세기를 맞춰 줘!", hint="손가락으로 세어 봐.")
LIGHTS_3 = Entry(id="lights-l-002", kind="MATH", dial="LIGHTS", age="LITTLE", answer=3, expr="1+2",
                 text="1 더하기 2는 얼마일까? 켜진 독서등이 모두 그 수만큼 되게 해 줘!", hint="손가락으로 세어 봐.")
SEAT_3 = Entry(id="seat_heat-l-002", kind="MATH", dial="SEAT_HEAT", age="LITTLE", answer=3, expr="2+1",
               text="2 더하기 1은 얼마일까? 의자 열선을 그 수와 같은 단으로 맞춰 줘!", hint="손가락으로 세어 봐.")
NO_DEVICE = "사과가 세 개 있었는데 두 개를 더 땄어. 사과는 모두 몇 개일까?"


@pytest.fixture(scope="module")
def shipped() -> Bank:
    return bank.load()


@pytest.mark.parametrize("e", [
    *ENTRIES, LETTERS, STEPS, HOT, POLLEN, FAN_3, LIGHTS_3, SEAT_3,
    # a situation told to the youngest suits the oldest as well
    replace(COOL, id="ac-on-d-900", age="DETECTIVE"),
    # an English question's hint may count in English too, a Korean particle right after the word included
    replace(WORD, id="temp_up-l-002", hint="ONE, TWO 하고 손가락을 펴며 세어 봐."),
    replace(WORD, id="temp_up-l-003", hint="ONE부터 차례로 세어 봐."),
    # the instruction's verb may be written without its space
    replace(SUM, id="fan-l-004", text="3 더하기 2는 얼마일까? 그 수만큼 바람 세기를 맞춰줘!"),
    replace(RUN, id="lights-l-003", text="켜진 독서등이 모두 1개, 2개, 3개 다음 수가 되게 해줘!"),
    replace(WORD, id="temp_up-l-004", text="two는 숫자로 몇일까? 그 수만큼 온도를 올려줘."),
], ids=lambda e: e.id)
def test_a_good_entry_of_every_kind_passes(e):
    assert bank.problems(e) == []


@pytest.mark.parametrize("e,expected", [
    (replace(SUM, answer=6), "답이 다름: 5 (정답 6)"),
    (replace(SUM, answer=9), "정답은 1~8: 9"),
    (replace(SUM, answer=None), "정답은 1~8: None"),
    (replace(SUM, age="BABY"), "모르는 종류, 장치나 나이: MATH FAN BABY"),
    (replace(SUM, dial="DOOR"), "모르는 종류, 장치나 나이: MATH DOOR LITTLE"),
    # each device asks only its own kinds
    (replace(RUN, dial="TEMP_UP", answer=3), "TEMP_UP에는 PATTERN 문제를 내지 않음"),
    (replace(RUN, dial="SEAT_HEAT", answer=3), "SEAT_HEAT에는 PATTERN 문제를 내지 않음"),
    (replace(SUM, dial="AC", answer=1), "AC에는 MATH 문제를 내지 않음"),
    (replace(FOG, dial="FAN"), "FAN에는 REASON 문제를 내지 않음"),
    (replace(FOG, kind="MATH"), "DEFROST에는 MATH 문제를 내지 않음"),
    # a situation's answer is the way its function goes: on (1) or off (0), or the one way a function has
    (replace(COOL, answer=2), "정답은 0~1: 2"),
    (replace(COOL, answer=None), "정답은 0~1: None"),
    (replace(FOG, answer=0), "정답은 1~1: 0"),
    (replace(HOT, answer=2), "정답은 1~1: 2"),
    # checked against its own device: the lights go up to 4, and a temperature question is not about the lights
    (replace(SUM, dial="LIGHTS"), "정답은 1~4: 5"),
    (replace(SUM, text=NO_DEVICE), "장치 이야기가 아님: 바람, 풍량, 송풍 중 하나가 없음"),
    (replace(WORD, dial="LIGHTS"), "장치 이야기가 아님: 독서등, 불빛, 불을, 불이, 불은, 전등, 빛 중 하나가 없음"),
    (replace(SEAT_3, text="2 더하기 1은 얼마일까? 그 수와 같은 단으로 맞춰 줘!"), "장치 이야기가 아님: 열선, 의자, 시트, 엉덩이 중 하나가 없음"),
    # a times sign, counting letters and a run by three are for the oldest only
    (replace(TIMES, age="CAPTAIN"), "이 나이에 쓰지 않는 셈: *"),
    (replace(LETTERS, age="LITTLE"), "이 나이에 긴 단어: BANANA (글자 수 3까지)"),
    (replace(LETTERS, age="CAPTAIN"), "이 나이에 긴 단어: BANANA (글자 수 5까지)"),
    (replace(STEPS, age="CAPTAIN"), "수가 너무 큼"),
    (replace(STEPS, age="CAPTAIN", terms=(10, 7, 4), answer=1, text="바람 세기를 10, 7, 4 다음에 오는 수의 단으로 맞춰 줘!"),
     "이 나이에 맞지 않는 규칙: -3씩"),
    (replace(SUM, hint="다섯 개가 되면 맞아."), "정답 수가 보임"),
    (replace(SUM, hint="정답은 다섯이야."), "정답 수가 보임"),
    (replace(WORD, hint="두 개를 떠올려 봐."), "정답 수가 보임"),
    (replace(SUM, hint="귀신이 같이 세어 줄 거야."), "무서운 말: 귀신"),
    (replace(FOG, hint="귀신이 입김을 불었나 봐."), "무서운 말: 귀신"),
    # English other than the question's word and number words, or in any other question's hint
    (replace(LETTERS, hint="BANANA와 CAR를 세어 봐."), "영어나 주소"),
    (replace(SUM, hint="ONE부터 세어 봐."), "영어나 주소"),
    # stray quotes or control characters a model's output can end in
    (replace(SUM, hint="손가락을 하나씩 펴면서 세어 봐.','"), "이상한 글자: 힌트"),
    (replace(SUM, text=SUM.text + "\n"), "이상한 글자: 문제"),
    (replace(FOG, text=FOG.text + "'"), "이상한 글자: 문제"),
], ids=lambda x: x.id if isinstance(x, Entry) else "")
def test_a_bad_entry_is_refused(e, expected):
    assert expected in bank.problems(e)


# ---------------------------------------------------------------- situations (REASON)

# a situation that keeps every rule; each case below breaks one
SITUATION = Entry(id="ac-c-900", kind="REASON", dial="AC", age="CAPTAIN", answer=1,
                  text="나 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 알맞은 차량 버튼을 찾아 나를 도와줘!",
                  hint="바람을 차갑게 만드는 버튼을 찾아봐.")


def told(text: str) -> Entry:
    return replace(SITUATION, text=text)


def test_the_hand_made_situation_passes():
    assert bank.problems(SITUATION) == []


@pytest.mark.parametrize("e,expected", [
    (told("나 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 나를 도와줘!"), "차량 버튼을 찾아 풀라는 부탁이 없음"),
    (replace(SITUATION, hint="바람을 차갑게 만드는 기능을 찾아봐."), "기능이라는 말"),
    (replace(SITUATION, hint="에어컨 버튼을 찾아봐."), "정답 버튼 이름: 에어컨"),
    (told("나 땀이 뻘뻘 나, 에어컨이 꺼져 있어서 안 시원해. 알맞은 차량 버튼을 찾아 나를 도와줘!"), "정답 버튼 이름: 에어컨"),
    # the chosen friend says it as "나": no friend is named, so any friend can say it
    (told("수달 요리사가 땀이 뻘뻘, 바람은 나오는데 안 시원하대. 알맞은 차량 버튼을 찾아 나를 도와줘!"), "이름: 수달"),
    (replace(SITUATION, hint="반테처럼 바람을 차갑게 만드는 버튼을 찾아봐."), "이름: 반테"),
    (told("나 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 알맞은 차량 버튼을 찾아 도와줘!"), "끝맺음이 '알맞은 차량 버튼을 찾아 나를 도와줘!'가 아님"),
    # the story before the question tells the cause, and the question suits any friend in any place
    (told("나 줄넘기를 해서 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 알맞은 차량 버튼을 찾아 나를 도와줘!"), "까닭(이야기가 정함): 줄넘기"),
    (told("놀이터에서 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 알맞은 차량 버튼을 찾아 나를 도와줘!"), "장소에 묶인 말: 놀이터"),
    (told("내 털이 땀에 푹 절었어, 바람은 나오는데 안 시원해. 알맞은 차량 버튼을 찾아 나를 도와줘!"), "몸 특징: 털"),
    (told("나 땀이 뻘뻘 나요, 바람은 나오는데 안 시원해요. 알맞은 차량 버튼을 찾아 도와주세요!"), "존댓말: 문제"),
    (told("여름이라 나 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 알맞은 차량 버튼을 찾아 나를 도와줘!"),
     "날씨, 계절, 밤낮을 단정: 여름"),
    (told("나 땀이 뻘뻘 나, 바람은 가장 세게 나오는데 안 시원해. 알맞은 차량 버튼을 찾아 나를 도와줘!"),
     "단계나 개수를 단정: 가장 세"),
    (told("차 안이 더워서 나 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 알맞은 차량 버튼을 찾아 나를 도와줘!"),
     "차 안 온도를 단정: 차 안이 더워"),
    # turning the air conditioner on is asked whatever the seat heat is, so the story cannot say it is on
    (told("열선은 켜져 있는데 나 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 알맞은 차량 버튼을 찾아 나를 도와줘!"),
     "조건에 없는 버튼 상태를 말함: {'SEAT_HEAT': 1}"),
    (told("나 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 어떤 차량 버튼을 찾아 누를까?"),
     "묻는 문장이 아니라 상황을 말하고 부탁하는 문장이어야 함"),
], ids=["no car buttons", "function", "own name in hint", "own name in text", "names a friend", "names a friend in hint",
        "wrong ending", "cause", "place", "body", "polite", "season", "level", "cabin temperature",
        "switch the condition does not hold", "asked"])
def test_a_situation_that_breaks_a_rule_is_refused(e, expected):
    assert expected in bank.problems(e), bank.problems(e)


def test_the_drivers_seat_situations_end_asking_for_the_guardian():
    seat = Entry(id="seat_heat_on-c-900", kind="REASON", dial="SEAT_HEAT_ON", age="CAPTAIN", answer=1,
                 text="안전 지킴이 의자가 차가워졌어. 차 안 공기는 괜찮으니 온도 숫자는 그대로 두고 알맞은 차량 버튼을 찾아 안전 지킴이를 도와줘!",
                 hint="안전 지킴이 의자를 데워 주는 의자 그림 버튼을 찾아봐.")
    assert bank.problems(seat) == []
    mine = replace(seat, text=seat.text.replace("안전 지킴이를 도와줘!", "나를 도와줘!"))
    assert "끝맺음이 '알맞은 차량 버튼을 찾아 안전 지킴이를 도와줘!'가 아님" in bank.problems(mine)


def test_a_number_question_in_polite_speech_is_refused():
    assert "존댓말: 힌트" in bank.problems(replace(SUM, hint="손가락을 하나씩 펴면서 세어 봐요."))
    assert "존댓말: 문제" in bank.problems(replace(SUM, text="3 더하기 2는 얼마일까? 그 수만큼 바람 세기를 맞춰 주세요."))


@pytest.mark.parametrize("e", [replace(HOT, age="LITTLE"), replace(POLLEN, age="LITTLE"),
                               replace(POLLEN, age="LITTLE", answer=0)], ids=["cooling", "recirc on", "recirc off"])
def test_the_youngest_are_never_told_recirculation_or_cooling(e):
    assert f"4~6세에는 내지 않는 조작: {e.dial}" in bank.problems(e)


@pytest.mark.parametrize("text,claims", [
    ("에어컨은 켜져 있는데도 조금 덥대.", {"AC": 1}),
    ("에어컨이 이미 켜져 있어.", {"AC": 1}),
    ("에어컨을 끄면 추워.", {"AC": 1}),
    ("열선은 꺼져 있어.", {"SEAT_HEAT": 0}),
    ("독서등은 켜 둔 채로 자.", {"LIGHTS": 1}),
    ("바람이 약해.", {}),
])
def test_what_a_sentence_says_is_switched_on_or_off(text, claims):
    assert bank.switch_claims(text) == claims


def test_a_switch_may_be_said_only_as_its_condition_holds_it():
    # lowering the temperature is asked only while the air conditioner is on (quiz.REASON_WHEN), so the story may say so
    said = replace(HOT, id="temp-down-c-900",
                   text="에어컨이 켜져 있는데도 나는 아직 조금 더워. 알맞은 차량 버튼을 찾아 나를 도와줘!")
    assert bank.switch_claims(said.text) == {"AC": 1} and bank.problems(said) == []
    # turning it on is asked while it is off
    wrong = told("나 땀이 뻘뻘 나, 에어컨은 켜져 있는데 안 시원해. 알맞은 차량 버튼을 찾아 나를 도와줘!")
    assert "조건에 없는 버튼 상태를 말함: {'AC': 1}" in bank.problems(wrong)


def test_switching_off_needs_a_clue_that_it_is_on():
    off = replace(SITUATION, id="ac-c-901", answer=0, hint="떨고 있는 나한테 시원한 바람이 지금도 필요할까?",
                  text="나 덜덜 떨려. 온도 숫자는 그대로 두고 알맞은 차량 버튼을 찾아 나를 도와줘!")
    assert "켜져 있는 것을 끄라는 단서가 없음" in bank.problems(off)
    clued = replace(off, text="나 덜덜 떨리는데 더울 때 켜는 시원한 바람이 켜져 있어. 알맞은 차량 버튼을 찾아 나를 도와줘!")
    assert bank.problems(clued) == []
    lights = Entry(id="lights_off-c-900", kind="REASON", dial="LIGHTS_OFF", age="CAPTAIN", answer=0,
                   text="작은 불이 켜져 있으면 별 스티커가 안 보여. 알맞은 차량 버튼을 찾아 나를 도와줘!",
                   hint="켜져 있는 작은 불을 전부 다 찾아봐.")
    assert "목표가 작은 불이 하나도 안 켜져 있게가 아님" in bank.problems(lights)
    every = replace(lights, text="작은 불이 하나도 안 켜져 있어야 별 스티커가 보여. 알맞은 차량 버튼을 찾아 나를 도와줘!")
    assert bank.problems(every) == []


def test_the_shipped_bank_has_three_situations_per_function_and_age(shipped):
    situations = Counter((e.dial, e.answer, e.age) for e in shipped.entries if e.kind == "REASON")
    assert situations == {(dial, value, age): 3 for (dial, value) in quiz.REASON_ACTIONS for age in bank.AGES
                          if not (age == "LITTLE" and dial in quiz.REASON_NOT_LITTLE)}
    assert sum(situations.values()) == 99


def test_the_youngest_get_no_cooling_situation_from_the_shipped_bank(shipped):
    q = quiz.parse("Q:REASON:TEMP_DOWN:1")
    # none at this age and no younger age to fall back on: the car asks its own
    assert shipped.pick(q, "LITTLE", random.Random(0)) is None
    for age in ("CAPTAIN", "DETECTIVE"):
        found, hint = shipped.pick(q, age, random.Random(0))
        e = next(e for e in shipped.entries if e.text == found.text)
        assert (e.kind, e.dial, e.answer, e.age, e.hint) == ("REASON", "TEMP_DOWN", 1, age, hint)
        assert found == bank.question(e)


# ---------------------------------------------------------------- reading, writing and picking


def test_a_bank_that_fails_its_own_checks_does_not_load(tmp_path):
    path = tmp_path / "bank.json"
    broken = replace(SUM, id="fan-l-009", answer=6)
    bank.write([SUM, broken], path)
    with pytest.raises(ValueError, match="fan-l-009"):
        bank.load(path)
    bank.write([SUM, replace(TIMES, id=SUM.id)], path)
    with pytest.raises(ValueError, match="duplicate ids"):
        bank.load(path)


def test_no_bank_file_is_an_empty_bank(tmp_path):
    assert bank.read(tmp_path / "missing.json") == [] and bank.load(tmp_path / "missing.json").entries == []


def test_write_and_read_round_trip(tmp_path):
    path = tmp_path / "bank.json"
    entries = [*ENTRIES, LETTERS, STEPS, HOT]
    bank.write(list(reversed(entries)), path)
    back = bank.read(path)
    assert sorted(back, key=lambda e: e.id) == sorted(entries, key=lambda e: e.id)
    assert bank.load(path).entries == back
    # by device, then kind, then age, then answer (off before on)
    assert [e.id for e in back] == [
        "fan-l-001", "fan-d-001", "fan-d-003", "fan-d-002", "temp_up-l-001", "temp-down-c-r02", "lights-l-001",
        "ac-off-l-r02", "ac-on-l-r03", "defrost-c-r02",
    ]
    raw = json.loads(path.read_text(encoding="utf-8"))
    assert raw["version"] == 2 and all("dial" in d for d in raw["questions"])
    situation = next(d for d in raw["questions"] if d["id"] == FOG.id)
    assert situation == {"id": FOG.id, "kind": "REASON", "dial": "DEFROST", "age": "CAPTAIN", "text": FOG.text,
                         "hint": FOG.hint, "answer": 1}
    assert next(d for d in raw["questions"] if d["id"] == COLD.id)["answer"] == 0
    assert "expr" not in next(d for d in raw["questions"] if d["id"] == WORD.id)


def test_pick_gives_the_question_and_its_hint():
    rnd = random.Random(0)
    q, hint = BANK.pick(quiz.parse(MATH), "CAPTAIN", rnd)
    assert q == bank.question(SUM) and hint == SUM.hint
    assert BANK.pick(quiz.parse(MATH), "DETECTIVE", rnd) == (bank.question(TIMES), TIMES.hint)
    assert BANK.pick(quiz.parse(UNCOVERED), "LITTLE", rnd) is None
    # a situation answers only the key of its own function and way
    assert BANK.pick(quiz.parse(REASON), "DETECTIVE", rnd) == (bank.question(FOG), FOG.hint)
    assert BANK.candidates(quiz.parse(AC), "DETECTIVE") == [COOL]
    assert BANK.candidates(quiz.parse("Q:REASON:AC:0"), "DETECTIVE") == [COLD]
    assert BANK.candidates(quiz.parse("Q:REASON:RECIRC:1"), "DETECTIVE") == []
    # an older age's question never goes to a younger child
    assert Bank([HOT]).candidates(quiz.parse("Q:REASON:TEMP_DOWN:1"), "LITTLE") == []
    assert Bank([HOT]).candidates(quiz.parse("Q:REASON:TEMP_DOWN:1"), "DETECTIVE") == [HOT]


def test_pick_never_gives_a_question_of_another_device():
    same = Bank([FAN_3, LIGHTS_3, SEAT_3])
    for seed in range(30):
        for e in (FAN_3, LIGHTS_3, SEAT_3):
            q = quiz.QuizKey("MATH", e.dial, 3)
            assert same.pick(q, "CAPTAIN", random.Random(seed)) == (bank.question(e), e.hint)
    # the bank has no temperature question with this answer, so it gives none rather than another device's
    assert same.pick(quiz.QuizKey("MATH", "TEMP_UP", 3), "CAPTAIN", random.Random(0)) is None
    one = replace(WORD, id="temp_up-l-009", answer=1, word="ONE", text="one은 숫자로 몇일까? 그 수만큼 온도를 올려 줘!")
    situations = Bank([FOG, COOL, COLD, HOT, one])
    for seed in range(30):
        rnd = random.Random(seed)
        for key, e in [(REASON, FOG), (AC, COOL), ("Q:REASON:AC:0", COLD), ("Q:REASON:TEMP_DOWN:1", HOT)]:
            assert situations.pick(quiz.parse(key), "DETECTIVE", rnd) == (bank.question(e), e.hint), key
    # a number question on the same device with the same value is not a situation
    assert situations.pick(quiz.parse("Q:REASON:TEMP_UP:1"), "DETECTIVE", random.Random(0)) is None


def test_on_the_air_conditioner_only_the_situation_for_that_on_or_off_is_picked():
    for seed in range(30):
        rnd = random.Random(seed)
        on, _ = BANK.pick(quiz.parse(AC), "CAPTAIN", rnd)
        off, _ = BANK.pick(quiz.parse("Q:REASON:AC:0"), "CAPTAIN", rnd)
        assert on.text == COOL.text and off.text == COLD.text
    assert Bank([COOL]).pick(quiz.parse("Q:REASON:AC:0"), "LITTLE", random.Random(0)) is None
    assert Bank([COLD]).pick(quiz.parse(AC), "LITTLE", random.Random(0)) is None


# ---------------------------------------------------------------- making the bank (tools/make_bank.py)


def test_the_list_to_read_has_every_entry_with_its_number():
    hot = replace(HOT, hint="바람은 그대로 | 숫자를 시원한 쪽으로 바꿔 봐.")
    entries = [*ENTRIES, LETTERS, STEPS, hot]
    md = markdown(entries)
    assert md.startswith(f"# 차근차근 탐험단 문제은행 ({len(entries)}개)")
    rows = [line for line in md.splitlines() if re.match(r"\| \d+ \|", line)]
    assert [int(r.split("|")[1]) for r in rows] == list(range(1, len(entries) + 1))
    assert all(sum(e.text in r for r in rows) == 1 for e in entries)
    row = {e.id: next(r for r in rows if e.text in r) for e in entries}
    # a number row has five cells and a situation row four, a "|" in a hint included
    for e in entries:
        assert row[e.id].count("|") == (5 if e.kind == "REASON" else 6), e.id
    assert row[COOL.id].split(" | ")[1:3] == ["4~6세(꼬마 탐험대)", COOL.text]
    numbers, situations = md.split(f"\n## {REASON_SECTION}\n")
    # number questions grouped by device, then kind, then age; every situation in its own part after them
    sections = numbers.split("\n## ")[1:]
    for e in entries:
        if e.kind == "REASON":
            assert e.text in situations, e.id
        else:
            assert next(sec for sec in sections if e.text in sec).startswith(DIAL_LABEL[e.dial]), e.id
    fan = next(sec for sec in sections if sec.startswith(DIAL_LABEL["FAN"]))
    assert fan.index("### 계산, 4~6세") < fan.index("### 계산, 10~12세") < fan.index("### 영어") < fan.index("### 규칙 찾기")
    # how many each device has of each number kind; a device with situations only is not in that table
    assert "| 풍량(바람 세기) | 2 | 1 | 1 | 4 |" in md and "| 온도 올리기 | 0 | 1 | 0 | 1 |" in md
    assert f"| {DIAL_LABEL['AC']} |" not in md
    # situations by function in the order of quiz.REASON_ACTIONS, with when each is asked and how many per age; the
    # youngest have none on the ones they are never told
    on, off = quiz.REASON_ACTIONS[("AC", 1)], quiz.REASON_ACTIONS[("AC", 0)]
    cooling = quiz.REASON_ACTIONS[("TEMP_DOWN", 1)]
    assert f"| {on} | {quiz.REASON_WHEN[('AC', 1)]} | 1 | 0 | 0 | 1 |" in situations
    assert f"| {cooling} | {quiz.REASON_WHEN[('TEMP_DOWN', 1)]} | - | 1 | 0 | 1 |" in situations
    assert situations.index(f"### {on}") < situations.index(f"### {off}") < situations.index(f"### {cooling}")
    assert f"### {quiz.REASON_ACTIONS[('RECIRC', 1)]}" not in situations


def test_the_cells_to_fill_add_up_to_the_whole_bank():
    per_age = {dial: sum(targets(dial).values()) for dial in bank.DIAL_ORDER}
    assert per_age == {"FAN": 32, "TEMP_UP": 12, "TEMP_DOWN": 12, "LIGHTS": 16, "SEAT_HEAT": 9, "AC": 6, "RECIRC": 6,
                       "DEFROST": 3, "FAN_UP": 3, "FAN_DOWN": 3, "SEAT_HEAT_ON": 3, "SEAT_HEAT_OFF": 3,
                       "LIGHT_CHILD": 3, "LIGHTS_OFF": 3}
    for dial in bank.DIAL_ORDER:
        for (kind, value), n in targets(dial).items():
            # only the kinds the device asks, and only answers a key of that kind can carry
            lo, hi = quiz.values(kind, dial)
            assert kind in quiz.KINDS_FOR[dial] and n > 0 and lo <= value <= hi
            if kind == "REASON":
                assert n == REASONS[(dial, value)] == 3
            else:
                assert n == PER_VALUE[dial][kind]
    assert set(targets("AC")) == {("REASON", 0), ("REASON", 1)}
    assert set(targets("TEMP_DOWN")) == {(k, v) for k in ("MATH", "ENGLISH") for v in (1, 2, 3)} | {("REASON", 1)}
    # three situations per function and age, none for the youngest on the ones they are never told
    cells = sum(n for dial in bank.DIAL_ORDER for age in bank.AGES for (kind, _), n in targets(dial).items()
                if kind == "REASON" and not (age == "LITTLE" and dial in quiz.REASON_NOT_LITTLE))
    assert cells == 99


def test_every_entry_falls_in_a_cell_of_its_device():
    assert [cell(e) for e in (SUM, FOG, COOL, COLD)] == [("MATH", 5), ("REASON", 1), ("REASON", 1), ("REASON", 0)]
    for e in [*ENTRIES, LETTERS, STEPS, HOT, POLLEN]:
        assert cell(e) in targets(e.dial), e.id


def test_a_written_situation_keeps_its_words_and_answer_only():
    written = {"kind": "REASON", "answer": 0, "expr": "1+1", "word": "x", "terms": [1, 2],
               "text": " 나 덜덜 떨리는데 더울 때 켜는 시원한 바람이 켜져 있어. 알맞은 차량 버튼을 찾아 나를 도와줘! ",
               "hint": " 감기 걸린 친구에게 시원한 바람이 지금도 필요할까? "}
    off = entry_from("AC", "CAPTAIN", written, "ac-c-909")
    assert off == Entry(id="ac-c-909", kind="REASON", dial="AC", age="CAPTAIN", answer=0, text=written["text"].strip(),
                        hint=written["hint"].strip())
    assert bank.problems(off) == []
    math = entry_from("LIGHTS", "LITTLE", {**written, "kind": "MATH", "answer": 3, "expr": " 1+2 ", "word": "x"},
                      "lights-l-009")
    assert (math.dial, math.answer, math.expr, math.word, math.terms) == ("LIGHTS", 3, "1+2", "", ())


def test_a_situation_request_says_when_the_app_asks_it():
    lines = request_lines("AC", "CAPTAIN", {("REASON", 1): 3, ("REASON", 0): 2}, ["이미 있는 상황"])
    text = "\n".join(lines)
    assert quiz.REASON_WHEN[("AC", 1)] in text and quiz.REASON_WHEN[("AC", 0)] in text
    assert "만들 문제: 상황 추리(kind REASON, answer 0: 에어컨 끄기) 2개, 상황 추리(kind REASON, answer 1: 에어컨 켜기) 3개" in lines
    assert f"상황 추리 난이도: {quiz.REASON_RULE['CAPTAIN']}" in lines
    # a situation needs no device word, so only number questions are asked for one
    assert not any("text에는 다음 말 중 하나를 꼭 넣는다" in line for line in lines)
    assert "이미 있는 문제(겹치지 않게): 이미 있는 상황" in lines


def fake_solver(answer: str):
    calls = []

    def create(**kwargs):
        calls.append(kwargs)
        return SimpleNamespace(content=[SimpleNamespace(type="text", text=answer)])

    return SimpleNamespace(messages=SimpleNamespace(create=create)), calls


def choosing(*actions: str):
    """A solver that picks these functions in turn, by their number in each shuffled list it is shown."""
    calls = []

    def create(**kwargs):
        calls.append(kwargs)
        want = actions[(len(calls) - 1) % len(actions)]
        prompt = kwargs["messages"][0]["content"]
        number = next(line.split(". ", 1)[0] for line in prompt.splitlines() if line.endswith(f". {want}"))
        return SimpleNamespace(content=[SimpleNamespace(type="text", text=number)])

    return SimpleNamespace(messages=SimpleNamespace(create=create)), calls


def test_the_blind_check_lists_every_situation_function_and_wants_the_entrys_own():
    order = reason_choices(random.Random(3))
    # every function, and what a child would try instead (turning the fan off, opening a window): a situation those
    # solve as well is turned down
    assert sorted(order) == sorted([*quiz.REASON_ACTIONS.values(), *REASON_DISTRACTORS])
    assert "바람 끄기" in order and "창문 열기" in order
    defrost, ac_on = quiz.REASON_ACTIONS[("DEFROST", 1)], quiz.REASON_ACTIONS[("AC", 1)]
    client, calls = choosing(defrost)
    assert blind_reason(client, FOG, random.Random(3))
    # five tries, each with the list shuffled again; the second model reads the situation and the numbered
    # functions, never the hint
    assert len(calls) == 5
    prompt = calls[0]["messages"][0]["content"]
    assert FOG.text in prompt and FOG.hint not in prompt
    assert all(f"{i}. {action}" in prompt for i, action in enumerate(order, 1))
    # right four times in five passes; right only by luck does not
    assert blind_reason(choosing(defrost, defrost, defrost, defrost, ac_on)[0], FOG, random.Random(3))
    assert not blind_reason(choosing(defrost, ac_on, defrost, ac_on, defrost)[0], FOG, random.Random(3))
    # two wrong answers already settle it, so the rest are not asked
    client, calls = choosing(ac_on)
    assert not blind_reason(client, FOG, random.Random(3)) and len(calls) == 2
    assert not blind_reason(fake_solver("모르겠어")[0], FOG, random.Random(3))


def test_situations_are_told_apart_by_their_words_only():
    # a situation has no sum, list or word, so two different ones on one function both stay
    other = replace(COOL, id="ac-on-l-r01",
                    text="나 헥헥 더워, 바람은 나오는데 안 시원해. 창문은 닫아 둔 채로 알맞은 차량 버튼을 찾아 나를 도와줘!")
    again = replace(COOL, id="ac-on-c-r09", age="CAPTAIN")
    assert [e.id for e in dedupe([again, COOL, other])] == [COOL.id, other.id]


# ---------------------------------------------------------------- number questions


def test_a_number_word_hint_must_not_tell_the_numbers_place():
    one = Entry(id="fan-c-900", kind="ENGLISH", dial="FAN", age="CAPTAIN", answer=1, word="ONE",
                text="one은 숫자로 몇일까? 그 수만큼 바람 세기를 맞춰 줘!", hint="ONE은 영어로 셀 때 맨 처음 나오는 말이야.")
    assert any("차례를 알려 줌" in p for p in bank.problems(one))
    counting = replace(one, hint="원, 투, 쓰리 하며 손가락을 하나씩 펴 봐.")
    assert bank.problems(counting) == []


def test_a_doubling_list_is_refused_and_has_to_say_so():
    plain = Entry(id="fan-d-900", kind="PATTERN", dial="FAN", age="DETECTIVE", answer=8, terms=(1, 2, 4),
                  text="바람 세기를 1, 2, 4 다음에 오는 수의 단으로 맞춰 줘!", hint="앞의 수에 무엇을 곱하는지 봐.")
    assert any("두 배씩" in p for p in bank.problems(plain))
    # no age doubles any more, so saying so is not enough
    said = bank.problems(replace(plain, text="바람 세기를 1, 2, 4 다음에 두 배가 되는 수의 단으로 맞춰 줘!"))
    assert said == ["규칙이 하나로 정해지지 않음"]


@pytest.mark.parametrize("text", ["2 더하기 1은 얼마일까? 그 수만큼 바람 세기를 맞춰 줘(지금)!",
                                  "2 더하기 1은 얼마일까? 그 수만큼 바람 세기를 맞춰 줘! ",
                                  "2 더하기 1은 얼마일까? 그 수만큼 바람 세기를 맞춰 줘!ㅤ"])
def test_brackets_and_hidden_characters_are_odd(text):
    e = Entry(id="fan-l-900", kind="MATH", dial="FAN", age="LITTLE", answer=3, expr="2+1", text=text, hint="손가락으로 세어 봐.")
    assert "이상한 글자: 문제" in bank.problems(e)


def test_an_older_age_does_not_get_the_same_sum_as_a_younger_one():
    little = Entry(id="fan-l-901", kind="MATH", dial="FAN", age="LITTLE", answer=3, expr="2+1",
                   text="2 더하기 1은 얼마일까? 그 수만큼 바람 세기를 맞춰 줘!", hint="손가락으로 세어 봐.")
    captain = replace(little, id="fan-c-901", age="CAPTAIN", text="2에 1을 더하면 얼마일까? 그 수만큼 바람 세기를 맞춰 줘!")
    other = replace(captain, id="fan-c-902", expr="4-1", text="4 빼기 1은 얼마일까? 그 수만큼 바람 세기를 맞춰 줘!")
    assert [e.id for e in dedupe([captain, little, other])] == ["fan-l-901", "fan-c-902"]


INSTRUCTION = "차 조작 지시가 아님"
ASKED = "묻는 문장이 아니라 지시하는 문장이어야 함"


@pytest.mark.parametrize("e,expected", [
    # a question to answer first, not the instruction itself
    (replace(SUM, text="3 더하기 2는 얼마일까? 그 수만큼 바람 세기를 맞추면 몇 단일까?"), ASKED),
    (replace(SUM, text="3 더하기 2는 얼마일까? 그 수만큼 바람 세기를 맞춰 줘?"), ASKED),
    (replace(WORD, text="two는 숫자로 몇일까? 그 수만큼 온도를 올려 줘. 몇 도 올라갔을까!"), ASKED),
    # the device without what to do with it, or another word for the device than the instruction's
    (replace(SUM, text="3 더하기 2는 얼마일까? 바람이 그 수면 좋겠어!"), INSTRUCTION),
    (replace(SUM, text="3 더하기 2는 얼마일까? 그 수만큼 송풍을 맞춰 줘!"), INSTRUCTION),
    # the other way round: warming on a cooling key, cooling the seat heat
    (replace(WORD, dial="TEMP_DOWN"), INSTRUCTION),
    (replace(SEAT_3, text="2 더하기 1은 얼마일까? 그 수만큼 의자 열선을 내려 줘!"), INSTRUCTION),
    # the reading lights are set by how many are on in all, not switched on by an amount
    (replace(RUN, text="독서등을 1개, 2개, 3개 다음 수만큼 켜 줘!"), INSTRUCTION),
])
def test_a_number_question_is_an_instruction_to_its_device(e, expected):
    assert any(p.startswith(expected) for p in bank.problems(e)), bank.problems(e)


def test_the_instruction_rule_names_what_is_missing():
    lights = replace(RUN, text="독서등을 1개, 2개, 3개 다음 수만큼 켜 줘!")
    assert "차 조작 지시가 아님: 독서등와 되게 해 줘/되게 해줘 중 하나가 있어야 함" in bank.problems(lights)
    cooling = replace(WORD, dial="TEMP_DOWN")
    assert "차 조작 지시가 아님: 온도와 내려 줘/내려줘 중 하나가 있어야 함" in bank.problems(cooling)


# ---------------------------------------------------------------- how a sum and a letter count are said


@pytest.mark.parametrize("e", [
    replace(SUM, id="temp_up-l-900", dial="TEMP_UP", answer=3, expr="2+1",
            text="2 더하기 1은 얼마일까? 추우니까 그 수만큼 온도를 올려 줘!"),
    replace(TIMES, id="fan-d-900", answer=6, expr="(8-5)*2", text="8 빼기 5에 2를 곱하면 얼마일까? 그 수만큼 바람 세기를 맞춰 줘!"),
    replace(LETTERS, id="lights-c-900", age="CAPTAIN", dial="LIGHTS", answer=4, word="FISH", hint="알파벳을 하나씩 손가락으로 세어 봐.",
            text="fish, 물고기를 영어로 쓰면 몇 글자일까? 켜진 독서등이 모두 그 수만큼 되게 해 줘!"),
    replace(LETTERS, id="temp_down-l-900", age="LITTLE", dial="TEMP_DOWN", answer=3, word="ICE", hint="ICE를 한 글자씩 짚어 봐.",
            text="ice, 얼음을 영어로 쓰면 몇 글자일까? 그 수만큼 온도를 내려 줘!"),
], ids=lambda e: e.id)
def test_a_sum_or_a_letter_count_asked_first_then_told_as_that_number_passes(e):
    assert bank.problems(e) == []


UNIT = "수 바로 뒤에 단위가 붙어 정답처럼 들림"
FORM = "식을 먼저 '얼마일까?'로 묻고 '그 수'로 지시해야 함"


@pytest.mark.parametrize("e,expected", [
    # heard aloud, the last number with its unit sounds like the answer (8단, 2도)
    (replace(TIMES, text="바람 세기를 4 곱하기 3 빼기 7단으로 맞춰 줘!"), UNIT),
    (replace(SUM, dial="TEMP_UP", answer=3, expr="2+1", text="2 더하기 1도만큼 온도를 올려 줘!"), UNIT),
    (replace(LIGHTS_3, text="켜진 독서등이 모두 1 더하기 2개가 되게 해 줘!"), UNIT),
    (replace(SUM, text="3 더하기 2는 얼마일까? 그 수만큼 바람 세기를 5단으로 맞춰 줘!"), UNIT),
    # without the question first, or without "그 수" after it
    (replace(SUM, dial="TEMP_UP", answer=3, expr="2+1", text="2 더하기 1만큼 온도를 올려 줘!"), FORM),
    (replace(SUM, text="3 더하기 2는 얼마일까? 바람 세기를 맞춰 줘!"), FORM),
    (replace(SUM, text="3 더하기 2를 바람 세기로 맞춰 줘! 얼마일까? 그 수야!"), FORM),
    # every number of the sum is in the question before "얼마일까?"
    (replace(TIMES, text="4 곱하기 3은 얼마일까? 그 수에서 7을 뺀 만큼 바람 세기를 맞춰 줘!"),
     "식의 수는 모두 묻는 말 앞에 있어야 함"),
    # 8은, 2는: the particle follows the number as it is read
    (replace(SUM, text="3 더하기 2은 얼마일까? 그 수만큼 바람 세기를 맞춰 줘!"), "숫자 뒤 조사가 틀림: 2은"),
    (replace(TIMES, text="4 곱하기 3 빼기 7는 얼마일까? 그 수만큼 바람 세기를 맞춰 줘!"), "숫자 뒤 조사가 틀림: 7는"),
], ids=["unit-dan", "unit-do", "unit-gae", "unit-after", "no-question", "no-that-number", "question-last",
        "number-after", "particle-2", "particle-7"])
def test_a_sum_said_so_a_number_sounds_like_the_answer_is_refused(e, expected):
    assert any(p.startswith(expected) for p in bank.problems(e)), bank.problems(e)


LETTER_FORM = "글자 수 문제는 '영어 단어, 우리말 뜻을 영어로 쓰면 몇 글자일까? 그 수...'로 말해야 함"


@pytest.mark.parametrize("e,expected", [
    # the screen does not show the spelling: the word and its meaning are said before the count
    (replace(LETTERS, text="banana의 글자 수만큼 바람 세기를 맞춰 줘!"), LETTER_FORM),
    (replace(LETTERS, text="바나나를 영어로 쓰면 몇 글자일까? banana, 그 수만큼 바람 세기를 맞춰 줘!"), LETTER_FORM),
    (replace(LETTERS, text="rabbit, 바나나를 영어로 쓰면 몇 글자일까? 그 수만큼 바람 세기를 맞춰 줘! banana"), LETTER_FORM),
    (replace(LETTERS, text="banana, 바나나을 영어로 쓰면 몇 글자일까? 그 수만큼 바람 세기를 맞춰 줘!"), "뜻 뒤 조사가 틀림: 바나나을"),
    (replace(LETTERS, text="BANANA, 바나나를 영어로 쓰면 몇 글자일까? 그 수만큼 바람 세기를 맞춰 줘!"), "문장 속 영어는 소문자로 써야 함"),
    # a short word or one that sounds like another, which a child cannot picture or spell by ear
    (replace(LETTERS, answer=2, word="ON", text="on, 켜짐을 영어로 쓰면 몇 글자일까? 그 수만큼 바람 세기를 맞춰 줘!"),
     "글자 수를 세기에 짧거나 헷갈리는 단어: ON"),
    (replace(LETTERS, word="SEA", text="sea, 바다를 영어로 쓰면 몇 글자일까? 그 수만큼 바람 세기를 맞춰 줘!"),
     "글자 수를 세기에 짧거나 헷갈리는 단어: SEA"),
    (replace(LETTERS, word="AIR", text="air, 공기를 영어로 쓰면 몇 글자일까? 그 수만큼 바람 세기를 맞춰 줘!"),
     "글자 수를 세기에 짧거나 헷갈리는 단어: AIR"),
], ids=["old-form", "word-last", "other-word", "particle", "capitals", "two-letters", "sound-alike", "nothing-to-picture"])
def test_a_letter_count_said_wrong_or_on_a_poor_word_is_refused(e, expected):
    assert any(p.startswith(expected) for p in bank.problems(e)), bank.problems(e)


NUMBER_FORM = "숫자 영단어 문제는 '영단어는 숫자로 몇일까? 그 수...'로 말해야 함"
SEVEN_FIVE = Entry(id="seat_heat-d-900", kind="ENGLISH", dial="SEAT_HEAT", age="DETECTIVE", answer=2, expr="SEVEN-FIVE",
                   text="seven 빼기 five는 얼마일까? 의자 열선을 그 수와 같은 단으로 맞춰 줘!",
                   hint="영어 숫자를 하나씩 우리말 숫자로 바꾼 다음 빼 봐.")


@pytest.mark.parametrize("e,expected", [
    # the word right before the unit, the old way
    (replace(WORD, dial="FAN", text="바람 세기를 two단으로 맞춰 줘!"), "영어 바로 뒤에 단위가 붙음: o단"),
    (replace(WORD, text="two만큼 온도를 올려 줘!"), "영어 바로 뒤에 단위가 붙음: o만큼"),
    (replace(WORD, text="two만큼 온도를 올려 줘!"), NUMBER_FORM),
    (replace(WORD, text="TWO는 숫자로 몇일까? 그 수만큼 온도를 올려 줘!"), "문장 속 영어는 소문자로 써야 함"),
    # the particle as the word sounds: two(투)는, seven(세븐)은
    (replace(WORD, text="two은 숫자로 몇일까? 그 수만큼 온도를 올려 줘!"), "영단어 뒤 조사가 틀림: two은"),
    (replace(WORD, text="one은 숫자로 몇일까? 그 수만큼 온도를 올려 줘!"), NUMBER_FORM),
    # a plain number word is too easy for the 10~12
    (replace(WORD, id="temp_up-d-900", age="DETECTIVE"), "10~12세는 숫자 영단어 하나 대신 영어 숫자 셈(seven 빼기 five)으로 냄"),
    # a sum said in English number words is asked like a sum
    (replace(SEVEN_FIVE, text="의자 열선을 seven 빼기 five단으로 맞춰 줘!"), "영어 바로 뒤에 단위가 붙음: e단"),
    (replace(SEVEN_FIVE, text="seven 빼기 five은 얼마일까? 의자 열선을 그 수와 같은 단으로 맞춰 줘!"), "영단어 뒤 조사가 틀림: five은"),
    (replace(SEVEN_FIVE, text="seven은 얼마일까? 의자 열선을 그 수에서 five를 뺀 단으로 맞춰 줘!"), "식의 수는 모두 묻는 말 앞에 있어야 함"),
    (replace(SEVEN_FIVE, age="CAPTAIN"), "이 나이는 영어 숫자 셈을 하지 않음"),
    # its own number words may be in its hint, other English may not
    (replace(SEVEN_FIVE, hint="seven에서 five를 빼고 cat도 세어 봐."), "영어나 주소"),
], ids=["unit-dan", "unit-mankeum", "old-form", "capitals", "particle", "other-word", "detective",
        "sum-unit", "sum-particle", "sum-after", "sum-captain", "sum-hint-english"])
def test_an_english_question_is_asked_first_and_said_by_its_age(e, expected):
    assert any(p.startswith(expected) for p in bank.problems(e)), bank.problems(e)


@pytest.mark.parametrize("e", [
    SEVEN_FIVE,
    replace(WORD, id="fan-c-901", dial="FAN", age="CAPTAIN", answer=7, word="SEVEN",
            text="seven은 숫자로 몇일까? 그 수만큼 바람 세기를 맞춰 줘!"),
    replace(LETTERS, id="fan-l-902", age="LITTLE", answer=3, word="CAT", hint="알파벳을 하나씩 손가락으로 세어 봐.",
            text="cat, 고양이를 영어로 쓰면 몇 글자일까? 그 수만큼 바람 세기를 맞춰 줘!"),
], ids=lambda e: e.id)
def test_an_english_question_of_every_form_passes(e):
    assert bank.problems(e) == []


@pytest.mark.parametrize("age,word,expected", [
    ("LITTLE", "SIX", "이 나이에 어려운 숫자 단어: SIX"),
    ("LITTLE", "FISH", "이 나이에 긴 단어: FISH (글자 수 3까지)"),
    ("CAPTAIN", "CAT", "이 나이의 글자 수 단어는 4~5글자: CAT"),
    ("CAPTAIN", "BANANA", "이 나이에 긴 단어: BANANA (글자 수 5까지)"),
    ("DETECTIVE", "APPLE", "이 나이의 글자 수 단어는 6~8글자: APPLE"),
])
def test_each_age_keeps_to_its_own_english(age, word, expected):
    number = word in quiz.NUMBER_WORDS
    text = (f"{word.lower()}{quiz.NUMBER_TOPIC[word]} 숫자로 몇일까? 그 수만큼 바람 세기를 맞춰 줘!" if number
            else f"{word.lower()}, 물건을 영어로 쓰면 몇 글자일까? 그 수만큼 바람 세기를 맞춰 줘!")
    e = Entry(id="fan-x-900", kind="ENGLISH", dial="FAN", age=age, answer=quiz.NUMBER_WORDS.get(word, len(word)),
              word=word, text=text, hint="알파벳을 하나씩 손가락으로 세어 봐.")
    assert expected in bank.problems(e)


def test_the_shipped_bank_says_every_number_question_the_new_way(shipped):
    sums = [e for e in shipped.entries if e.kind == "MATH"]
    english = [e for e in shipped.entries if e.kind == "ENGLISH"]
    letters = [e for e in english if quiz.letter_count(e.word)]
    numbers = [e for e in english if e.word in quiz.NUMBER_WORDS]
    english_sums = [e for e in english if e.expr]
    assert sums and letters and numbers and english_sums
    assert len(letters) + len(numbers) + len(english_sums) == len(english)
    assert all(bank.MATH_FORM.search(e.text) and not bank.MATH_UNIT.search(e.text) for e in sums + english_sums)
    assert all(bank.NUMBER_FORM.search(e.text) for e in numbers)
    assert all(not bank.ENGLISH_UNIT.search(e.text) and e.text == e.text.replace(e.word, e.word.lower()) for e in english)
    assert all(e.answer == len(e.word) and e.text.startswith(e.word.lower() + ", ") for e in letters)
    # each age's English: one~five and 3 letters, number words and 4~5 letters, sums and 6~8 letters
    assert all(quiz.NUMBER_WORDS[e.word] <= 5 for e in numbers if e.age == "LITTLE")
    assert all(e.age != "DETECTIVE" for e in numbers) and all(e.age == "DETECTIVE" for e in english_sums)
    for e in letters:
        lo, hi = quiz.LEVELS[e.age].letters
        assert lo <= len(e.word) <= hi, e.id


def test_the_shipped_bank_has_a_question_for_every_key_an_age_can_be_asked(shipped):
    for dial in quiz.DIALS:
        for kind in quiz.KINDS_FOR[dial]:
            lo, hi = quiz.values(kind, dial)
            for v in range(lo, hi + 1):
                q = quiz.QuizKey(kind, dial, v)
                for age in bank.AGES:
                    never = kind == "REASON" and age == "LITTLE" and dial in quiz.REASON_NOT_LITTLE
                    beyond = kind == "ENGLISH" and v > quiz.english_reach(age)
                    assert bool(shipped.candidates(q, age)) != (never or beyond), (q.key, age)
    # the 10~12 never fall back to a plain number word
    for e in shipped.entries:
        if e.kind == "ENGLISH":
            picked = shipped.candidates(quiz.QuizKey("ENGLISH", e.dial, e.answer), "DETECTIVE")
            assert all(x.word not in quiz.NUMBER_WORDS for x in picked), (e.dial, e.answer)
