"""Each episode draws its questions from the bank, fitted to the youngest child, and the writer only tells the story
around them."""
import random

import pytest

from app import quiz
from app.models import EpisodeRequest
from app.service import make_episode, pick_questions, put_questions
from tests.fakes import (AC, BANK, COLD, COOL, ENGLISH, FOG, FRIEND, MATH, REASON, RUN, SUM, TIMES, UNCOVERED, WORD, FakeWriter,
                         draft, without_situations)

AGES = ("LITTLE", "CAPTAIN", "DETECTIVE")


def request(slots, ages=("CAPTAIN",)):
    return EpisodeRequest(
        installId="0123456789abcdef0123",
        ages=list(ages),
        minutes=10,
        context={"timeOfDay": "EVENING", "season": "WINTER"},
        puzzles=slots,
        friend=FRIEND,
    )


def picked_text(key, ages, seed=0):
    return pick_questions(request([[key]], ages), BANK, random.Random(seed))[key][0].text


def test_every_quiz_key_gets_a_bank_question_and_its_hint():
    picks = pick_questions(request([[MATH], [ENGLISH, "Q:PATTERN:LIGHTS:4"], ["BELT"]]), BANK, random.Random(0))
    assert set(picks) == {MATH, ENGLISH, "Q:PATTERN:LIGHTS:4"}
    assert picks[MATH][0].expr == SUM.expr and picks[MATH][1] == SUM.hint
    assert picks[ENGLISH][0].word == WORD.word and picks[ENGLISH][1] == WORD.hint
    assert picks["Q:PATTERN:LIGHTS:4"][0].terms == list(RUN.terms) and picks["Q:PATTERN:LIGHTS:4"][1] == RUN.hint


def test_a_key_the_bank_has_nothing_for_is_left_out():
    assert pick_questions(request([[UNCOVERED], [MATH]]), BANK, random.Random(0)).keys() == {MATH}
    assert pick_questions(request([[REASON], [AC]]), without_situations(), random.Random(0)) == {}


def test_the_question_fits_the_youngest_child_on_board():
    assert picked_text(MATH, ["DETECTIVE"]) == TIMES.text
    # a younger child on board gets the easier question, even when the oldest has one of their own
    assert picked_text(MATH, ["DETECTIVE", "LITTLE"]) == SUM.text
    assert picked_text(MATH, ["LITTLE"]) == SUM.text


@pytest.mark.parametrize("key,ages", [(MATH, ["CAPTAIN"]), (MATH, ["DETECTIVE", "CAPTAIN"]), (ENGLISH, ["DETECTIVE"])])
def test_an_age_the_bank_has_nothing_for_gets_a_younger_ages_question(key, ages):
    assert picked_text(key, ages) in (SUM.text, WORD.text)
    assert BANK.candidates(quiz.parse(key), "CAPTAIN") == BANK.candidates(quiz.parse(key), "LITTLE")


@pytest.mark.parametrize("key,e", [(REASON, FOG), (AC, COOL), ("Q:REASON:AC:0", COLD)])
@pytest.mark.parametrize("age", AGES)
def test_a_situation_key_gets_the_situation_written_for_its_function_and_way(key, e, age):
    q = quiz.parse(key)
    if age == "LITTLE" and q.dial in quiz.REASON_NOT_LITTLE:
        # never told to the youngest: the bank has none for them and no younger age to fall back on
        assert key not in pick_questions(request([[key]], [age]), BANK, random.Random(0))
        return
    for seed in range(10):
        dq, hint = pick_questions(request([[key]], [age]), BANK, random.Random(seed))[key]
        assert (dq.kind, dq.text, hint) == ("REASON", e.text, e.hint)
        assert quiz.check_question(q, dq, age) == []


def test_put_questions_fills_only_the_picked_scenes():
    req = request([[MATH], [UNCOVERED], ["BELT"]])
    d = draft([MATH, "BELT"])
    d.scenes.insert(1, d.scenes[0].model_copy(update={"key": UNCOVERED}))
    picks = pick_questions(req, BANK, random.Random(0))
    put_questions(d, picks)
    math, uncovered, belt = d.scenes
    assert (math.question, math.hint) == picks[MATH]
    assert uncovered.question is None and uncovered.hint == ""
    assert belt.question is None and belt.hint == "끝까지 넣으면 딸깍 소리가 나."


def test_make_episode_puts_the_picked_questions_in_the_draft_and_the_episode():
    req = request([[MATH], [REASON], ["BELT"]])
    ep, d = make_episode(req, FakeWriter(draft([MATH, REASON, "BELT"])), BANK, rnd=random.Random(0))
    assert ep.dropped == [] and d.scenes[0].question.text == SUM.text
    assert ep.scenes[MATH].question.text == SUM.text and ep.scenes[MATH].hint == SUM.hint
    assert d.scenes[1].question.text == FOG.text
    assert (ep.scenes[REASON].question.kind, ep.scenes[REASON].question.text) == ("REASON", FOG.text)
    assert ep.scenes[REASON].hint == FOG.hint


def test_a_quiz_hint_the_writer_wrote_anyway_is_replaced_by_the_banks():
    req = request([[MATH], ["BELT"]])
    leaky = draft([MATH, "BELT"])
    leaky.scenes[0].hint = "다섯 개가 되면 맞아."
    w = FakeWriter(leaky)
    ep, _ = make_episode(req, w, BANK, rnd=random.Random(0))
    assert w.calls == 1 and ep.dropped == [] and ep.scenes[MATH].hint == SUM.hint
