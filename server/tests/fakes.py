"""Hand-written drafts that pass every rule, a small question bank for them, and a writer that hands them out."""
from app import bank
from app.bank import Bank, Entry
from app.models import Draft, DraftQuestion, DraftScene
from app.service import put_questions

# one key of every kind; together with BELT they fill the six puzzle slots of a request
MATH = "Q:MATH:FAN:5"
ENGLISH = "Q:ENGLISH:TEMP_UP:2"
PATTERN = "Q:PATTERN:LIGHTS:4"
# two situations: the front window defroster, and the air conditioner switched on
REASON = "Q:REASON:DEFROST:1"
AC = "Q:REASON:AC:1"
# a quiz key the bank below has no question for
UNCOVERED = "Q:MATH:FAN:7"
# the friend the child picked in every request of these tests
FRIEND = "BANTE"


def question(kind: str, text: str, expr: str = "", word: str = "", terms=()) -> DraftQuestion:
    return DraftQuestion(kind=kind, text=text, expr=expr, word=word, terms=list(terms))


# every number question is an instruction to the device its key sets, with the amount as a little puzzle
# (bank.problems); made for the youngest, so they also serve the older ages (Bank.candidates falls back to a younger
# age)
SUM = Entry(id="fan-l-001", kind="MATH", dial="FAN", age="LITTLE", answer=5, expr="3+2",
            text="3 더하기 2는 얼마일까? 그 수만큼 바람 세기를 맞춰 줘!", hint="손가락을 하나씩 펴면서 세어 봐.")
WORD = Entry(id="temp_up-l-001", kind="ENGLISH", dial="TEMP_UP", age="LITTLE", answer=2, word="TWO",
             text="two는 숫자로 몇일까? 그 수만큼 온도를 올려 줘!", hint="영어로 수를 하나부터 차례로 세어 봐.")
RUN = Entry(id="lights-l-001", kind="PATTERN", dial="LIGHTS", age="LITTLE", answer=4, terms=(1, 2, 3),
            text="켜진 독서등이 모두 1개, 2개, 3개 다음 수가 되게 해 줘!", hint="수가 얼마씩 커지는지 봐.")
# situations from the shipped bank (app/question_bank.json)
# (the 4~6 are not told a windscreen situation, quiz.REASON_NOT_LITTLE: this one is for the 7~9)
FOG = Entry(id="defrost-c-r02", kind="REASON", dial="DEFROST", age="CAPTAIN", answer=1,
            text="앞 유리 안쪽이 자꾸 뿌예지려고 해. 손으로 문지르면 얼룩이 남으니까 알맞은 차량 버튼을 찾아 나를 도와줘!",
            hint="닦는 대신 버튼 그림을 봐, 꼬불꼬불 화살표 버튼이 두 개면 둥그스름한 앞 유리 모양 쪽이야.")
# an air conditioner situation is written for turning it on (1) or off (0) and only serves that answer
COOL = Entry(id="ac-on-l-r03", kind="REASON", dial="AC", age="LITTLE", answer=1,
             text="땀방울이 그림 종이에 똑 떨어졌어, 바람은 나오는데 안 시원해. 창문은 닫아 둔 채로 알맞은 차량 버튼을 찾아 나를 도와줘!",
             hint="바람을 시원하게 만들어 주는 버튼이 어디 있을지 찾아봐.")
COLD = Entry(id="ac-off-l-r02", kind="REASON", dial="AC", age="LITTLE", answer=0,
             text="나 으슬으슬 추워, 더울 때 켜는 시원한 바람이 켜져 있어. 온도 숫자는 그대로 두고 알맞은 차량 버튼을 찾아 나를 도와줘!",
             hint="추운 나한테 더울 때 쓰는 버튼이 켜져 있어도 될까?")
# the same answer as SUM with a times sign, for the oldest only
TIMES = Entry(id="fan-d-001", kind="MATH", dial="FAN", age="DETECTIVE", answer=5, expr="4*3-7",
              text="4 곱하기 3 빼기 7은 얼마일까? 그 수만큼 바람 세기를 맞춰 줘!", hint="먼저 곱하고 나서 빼 봐.")
ENTRIES = [SUM, WORD, RUN, TIMES, FOG, COOL, COLD]
BANK = Bank(ENTRIES)


def without_situations() -> Bank:
    return Bank([e for e in ENTRIES if e.kind != "REASON"])


# key -> (bubble, speech, hint, question, result): the lines the writer writes, and for a quiz the hint and question
# the bank gives it; every quiz passes for the LITTLE and CAPTAIN ages
GOOD = {
    MATH: (
        "바람개비가 멈췄어!",
        "발명품 바람개비가 멈춰 버렸어. 바람이 몇 단인지 알아내면 바람개비를 돌릴 수 있어!",
        SUM.hint,
        bank.question(SUM),
        "바람이 솔솔 나오지? 바람개비가 빙글빙글 돌아! 이제 하나만 더 고치면 돼.",
    ),
    ENGLISH: (
        "으슬으슬해!",
        "부르르, 차가운 연못에서 부품을 건졌더니 몸이 떨려. 영어 낱말을 맞히면 차 안을 조금 따뜻하게 할 수 있어!",
        WORD.hint,
        bank.question(WORD),
        "온도를 올렸으니 곧 포근해질 거야. 이제 다음 부품을 찾으러 가자!",
    ),
    PATTERN: (
        "창고가 깜깜해!",
        "부품 창고가 깜깜해졌어. 불이 켜지는 규칙을 찾으면 작은 불을 밝힐 수 있어!",
        RUN.hint,
        bank.question(RUN),
        "작은 불이 환하게 켜졌어! 반짝 부품이 보여. 이제 조립만 하면 돼.",
    ),
    REASON: (
        "앞이 뿌예!",
        "부품을 조이느라 호호 숨을 몰아쉬었더니 공기가 축축해졌어. 이 문제를 풀어 볼까?",
        FOG.hint,
        bank.question(FOG),
        "유리가 맑아지고 있어! 저 너머 대회장이 보여. 이제 첫 부품을 끼우러 가자.",
    ),
    AC: (
        "헥헥, 더워!",
        "다리 나무 판을 고치느라 쾅쾅 망치질을 했더니 털이 축축해. 이 문제를 풀어 볼까?",
        COOL.hint,
        bank.question(COOL),
        "에어컨 바람이 시원하지? 땀이 쏙 들어갔어! 이제 다리 기둥을 고치러 가자.",
    ),
    "BELT": ("벨트를 딸깍!", "탐험대장, 벨트를 딸깍! 이미 맸다면 버클을 짚고 딸깍 확인!", "끝까지 넣으면 딸깍 소리가 나.", None,
             "딸깍, 준비 끝! 다음 곳으로 가 보자."),
}


def draft(keys, **override) -> Draft:
    """A draft as the writer returns it: the story only, with a quiz scene's hint left empty for the bank's."""
    scenes = [DraftScene(key=k, bubble=GOOD[k][0], speech=GOOD[k][1], hint="" if GOOD[k][3] else GOOD[k][2],
                         result=GOOD[k][4])
              for k in keys]
    fields = dict(
        theme="STAR_TRAIN",
        title="별빛 기차를 구해라",
        intro="안녕, 탐험대장! 나는 반테야. 별빛 기차가 멈췄대. 같이 고쳐 보자!",
        treasureBadge="기차를 다 고쳤어! 탐험대장, 탐험 배지 받아!",
        scenes=scenes,
    )
    fields.update(override)
    return Draft(**fields)


def filled(keys, **override) -> Draft:
    """The writer's draft once every quiz scene has its question and hint, as the server checks it."""
    d = draft(keys, **override)
    put_questions(d, {k: (GOOD[k][3].model_copy(deep=True), GOOD[k][2]) for k in keys if GOOD[k][3] is not None})
    return d


class FakeWriter:
    """Hands out the given drafts (or raises the given errors) in order, and keeps every message it was sent."""

    def __init__(self, *drafts):
        self.drafts = list(drafts)
        self.calls = 0
        self.messages: list[str] = []

    def write(self, system, user):
        self.calls += 1
        self.messages.append(user)
        d = self.drafts.pop(0)
        if isinstance(d, Exception):
            raise d
        return d, "fake-model"


def leaky():
    """A draft whose story says the sum's answer before the child has worked it out."""
    d = draft([MATH, REASON, "BELT"])
    d.scenes[0].speech = "사과 다섯 개만 있으면 된대! 이 문제를 풀어 볼까?"
    return d
