"""Quiz puzzles: the app picks the answer (a car device and a value) and the writer makes a question whose answer is
that value. This module works the answer out again from the question's parts, so a question the writer got wrong, or
one too hard for the youngest child, never reaches the car.

A quiz key is ``Q:<KIND>:<DIAL>:<VALUE>``, e.g. ``Q:MATH:FAN:5`` (a sum whose answer sets the fan to 5) or
``Q:REASON:AC:1`` (a situation the child solves by turning the air conditioner on). The car app builds the same keys
(car StoryKeys.kt).
"""
import ast
import re
import unicodedata
from dataclasses import dataclass

from .text_guard import clean_text, norm

KINDS = ("MATH", "ENGLISH", "PATTERN", "REASON")
KIND_LABEL = {"MATH": "계산", "ENGLISH": "영어", "PATTERN": "규칙 찾기", "REASON": "추론"}

# the values each device can take as an answer. The number devices take an amount; the situation devices (REASON)
# take which way the function goes: AC and RECIRC are 1 for on and 0 for off, the others have one value only
DIALS = {
    "FAN": (1, 8),
    "TEMP_UP": (1, 3),
    "TEMP_DOWN": (1, 3),
    "LIGHTS": (1, 4),
    "SEAT_HEAT": (1, 3),
    "AC": (0, 1),
    # 내기 순환: 1 keeps the outside air out, 0 lets it in
    "RECIRC": (0, 1),
    # the front window defroster, switched on
    "DEFROST": (1, 1),
    # the fan stronger or weaker than when the puzzle began
    "FAN_UP": (1, 1),
    "FAN_DOWN": (1, 1),
    # the driver seat heat on (any step) or off
    "SEAT_HEAT_ON": (1, 1),
    "SEAT_HEAT_OFF": (0, 0),
    # a back-seat reading light on, or every reading light off
    "LIGHT_CHILD": (1, 1),
    "LIGHTS_OFF": (0, 0),
}

# the kinds each device asks: temperature and seat heat answers (1~3) are too small for a number sequence that reads
# naturally, and a situation (REASON) asks for a function, never an amount
KINDS_FOR = {
    "FAN": ("MATH", "ENGLISH", "PATTERN"),
    "LIGHTS": ("MATH", "ENGLISH", "PATTERN"),
    "SEAT_HEAT": ("MATH", "ENGLISH"),
    "TEMP_UP": ("MATH", "ENGLISH", "REASON"),
    "TEMP_DOWN": ("MATH", "ENGLISH", "REASON"),
    "AC": ("REASON",),
    "RECIRC": ("REASON",),
    "DEFROST": ("REASON",),
    "FAN_UP": ("REASON",),
    "FAN_DOWN": ("REASON",),
    "SEAT_HEAT_ON": ("REASON",),
    "SEAT_HEAT_OFF": ("REASON",),
    "LIGHT_CHILD": ("REASON",),
    "LIGHTS_OFF": ("REASON",),
}


def values(kind: str, dial: str) -> tuple[int, int]:
    """The answers a key of this kind can carry on this device: a situation on the temperature only says which way
    it goes, so its value is always 1."""
    if kind == "REASON" and dial in ("TEMP_UP", "TEMP_DOWN"):
        return 1, 1
    return DIALS[dial]


# what the child does to solve a situation, as the parent hears it; the blind check lists all of them
REASON_ACTIONS = {
    ("AC", 1): "에어컨 켜기",
    ("AC", 0): "에어컨 끄기",
    ("RECIRC", 1): "내기 순환 켜기",
    ("RECIRC", 0): "내기 순환 끄기",
    ("DEFROST", 1): "앞 유리 성에 제거 켜기",
    ("TEMP_UP", 1): "온도 올리기",
    ("TEMP_DOWN", 1): "온도 내리기",
    ("FAN_UP", 1): "바람 세게 하기",
    ("FAN_DOWN", 1): "바람 약하게 하기",
    ("SEAT_HEAT_ON", 1): "운전석 열선 켜기",
    ("SEAT_HEAT_OFF", 0): "운전석 열선 끄기",
    ("LIGHT_CHILD", 1): "뒷자리 독서등 켜기",
    ("LIGHTS_OFF", 0): "독서등 모두 끄기",
}

# when the car app asks a situation: never while the car already shows its answer, and only while the story fits the
# car (the app checks these on the car state, car Puzzles.kt; the question list shows them)
REASON_WHEN = {
    ("AC", 1): "에어컨이 꺼져 있고 바람이 1단 이상이며 운전석 온도가 26도 이하일 때",
    ("AC", 0): "에어컨이 켜져 있고 바람이 1단 이상이며 운전석 온도가 22도 미만일 때",
    ("RECIRC", 1): "내기 순환이 꺼져 있고 바람이 1단 이상일 때",
    ("RECIRC", 0): "내기 순환이 켜져 있고 바람이 1단 이상일 때",
    ("DEFROST", 1): "앞 유리 성에 제거가 꺼져 있을 때",
    ("TEMP_UP", 1): "에어컨이 꺼져 있고 바람이 1단 이상이며 운전석 온도가 22도 미만일 때",
    ("TEMP_DOWN", 1): "에어컨이 켜져 있고 바람이 1단 이상이며 운전석 온도가 22도 이상일 때",
    ("FAN_UP", 1): "바람 세기가 1~3단일 때",
    ("FAN_DOWN", 1): "바람 세기가 6단 이상일 때",
    ("SEAT_HEAT_ON", 1): "운전석 열선이 꺼져 있을 때",
    ("SEAT_HEAT_OFF", 0): "운전석 열선이 켜져 있을 때",
    ("LIGHT_CHILD", 1): "뒷자리 독서등이 모두 꺼져 있을 때",
    ("LIGHTS_OFF", 0): "독서등이 하나라도 켜져 있을 때",
}

# the youngest (4~6) do not know the recirculation button, and hear "에어컨" in a cooling story as the answer. Warming
# the air (they reach for the seat), the inside of the windscreen, and every reading light off at once take a second
# thought they do not have yet: they are told only situations one clue settles
REASON_NOT_LITTLE = ("RECIRC", "TEMP_DOWN", "TEMP_UP", "DEFROST", "LIGHTS_OFF")

# a situation never says the name of its own button; another button's name may only stand in a clue that rules it
# out ("에어컨은 켜져 있는데도")
REASON_OWN = {
    "AC": ("에어컨",), "RECIRC": ("내기", "외기", "순환"), "DEFROST": ("성에",), "TEMP_UP": ("온도", "히터"),
    "TEMP_DOWN": ("온도", "히터"), "FAN_UP": ("풍량", "바람 세기"), "FAN_DOWN": ("풍량", "바람 세기"),
    "SEAT_HEAT_ON": ("열선",), "SEAT_HEAT_OFF": ("열선",), "LIGHT_CHILD": ("독서등",), "LIGHTS_OFF": ("독서등",),
}

# the car's button and control names: a story around a situation never says them, the child works the function out from the trouble
BUTTON_NAMES = (
    "내기 순환", "외기", "순환", "성에 제거", "성에", "풍량", "바람 세기", "풍향", "송풍", "공조",
    "열선", "독서등", "에어컨", "히터", "온도", "버튼", "스위치", "다이얼",
)
# direct operation instructions a situation's story must not tell before the question ("켜기", "시원한 바람을 켜 줘", "눌러 봐"...)
REASON_ACTION_WORDS = ("켜기", "끄기")
_REASON_DIRECTIVE = re.compile(
    r"(?<![시비지들밝익])켜\s*(?:줘|봐|자|달라)"
    r"|(?<![가-힣])(?:꺼\s*(?:줘|봐|자|달라)|끄\s*자)"
    r"|(?:눌러|맞춰|올려|내려|줄여|높여|낮춰)\s*(?:줘|봐|자|달라)"
)


def action_words(text: str) -> list[str]:
    """Direct manipulation words in a situation's story (speech), where only the cause should be told."""
    t = norm(text)
    found = [w for w in REASON_ACTION_WORDS if norm(w) in t]
    depunct = re.sub(r"[^\w\s]+|_+", "", clean_text(text))
    for m in _REASON_DIRECTIVE.finditer(depunct):
        if m.group(0) not in found:
            found.append(m.group(0))
    return found

# a bank question is an instruction to the car with the amount as a little puzzle ("one은 숫자로 몇일까? 그 수만큼 온도를 내려 줘!"): the
# child works out the amount and puts it in by doing it. It names the device and says what to do with it
INSTRUCTION = {
    "FAN": (("바람",), ("맞춰 줘", "맞춰줘", "해 줘", "해줘")),
    "TEMP_UP": (("온도",), ("올려 줘", "올려줘")),
    "TEMP_DOWN": (("온도",), ("내려 줘", "내려줘")),
    "LIGHTS": (("독서등",), ("되게 해 줘", "되게 해줘")),
    "SEAT_HEAT": (("열선",), ("맞춰 줘", "맞춰줘", "올려 줘", "올려줘")),
}

# a bank number question is about its device: one of these words is in it (car Lines.kt asks the same devices)
DEVICE_WORDS = {
    "FAN": ("바람", "풍량", "송풍"),
    "LIGHTS": ("독서등", "불빛", "불을", "불이", "불은", "전등", "빛"),
    "SEAT_HEAT": ("열선", "의자", "시트", "엉덩이"),
    "TEMP_UP": ("온도", "따뜻", "추워", "추운"),
    "TEMP_DOWN": ("온도", "시원", "더워", "더운"),
}

QUESTION_MAX = 100
EXPR_MAX = 40


@dataclass(frozen=True)
class QuizKey:
    kind: str
    dial: str
    value: int

    @property
    def key(self) -> str:
        return f"Q:{self.kind}:{self.dial}:{self.value}"


def parse(key: str) -> QuizKey | None:
    """None for a key that is not a quiz key; ValueError for a quiz key the app could not have sent."""
    if not key.startswith("Q:"):
        return None
    parts = key.split(":")
    if len(parts) != 4:
        raise ValueError(f"bad quiz key: {key}")
    _, kind, dial, value = parts
    if kind not in KINDS or dial not in DIALS or not value.isdigit():
        raise ValueError(f"bad quiz key: {key}")
    if kind not in KINDS_FOR[dial]:
        raise ValueError(f"{dial} does not ask {kind}: {key}")
    lo, hi = values(kind, dial)
    if not lo <= int(value) <= hi:
        raise ValueError(f"answer out of range: {key}")
    return QuizKey(kind, dial, int(value))


# ---------------------------------------------------------------- difficulty by age


@dataclass(frozen=True)
class Level:
    label: str
    ops: frozenset[str]
    max_ops: int
    max_operand: int
    # the largest number either side of a × or ÷ may be (0: no × or ÷ at this age)
    max_factor: int
    english_max: int
    # how long a letter-counting word may be at this age; the bank keeps each age to the whole range (bank.age_problems),
    # a question picked for an older child may be shorter
    letters: tuple[int, int]
    step_max: int
    step_down: bool
    ratio: bool
    max_term: int
    # sums said with English number words ("seven 빼기 five"), for the oldest only
    english_sums: bool = False


LEVELS = {
    # counting down by one is allowed too: counting up from 0 can never end at 1 or 2
    # heard aloud in a car, one small step is plenty: the youngest add or take away once, the oldest at most twice
    # number words: one~five for the youngest, up to ten after that; letter counts: 3 letters, 4~5, 6~8
    "LITTLE": Level("덧셈이나 뺄셈 한 번, 5 이하의 수", frozenset("+-"), 1, 5, 0, 5, (3, 3), 1, True, False, 10),
    "CAPTAIN": Level("덧셈이나 뺄셈 한 번, 10 이하의 수", frozenset("+-"), 1, 10, 0, 10, (4, 5), 2, True, False, 10),
    "DETECTIVE": Level("덧셈, 뺄셈, 곱셈 두 번까지, 12 이하의 수(곱하는 수는 5 이하)",
                       frozenset("+-*"), 2, 12, 5, 10, (6, 8), 3, True, False, 20, english_sums=True),
}

PATTERN_RULE = {
    "LITTLE": "1씩 커지거나 1씩 작아지는 수",
    "CAPTAIN": "1이나 2씩 커지거나 작아지는 수",
    "DETECTIVE": "1~3씩 커지거나 작아지는 수",
}

# how a situation is told at each age, and how long it may be heard aloud
REASON_RULE = {
    "LITTLE": "단서 하나로 바로 알 수 있는 상황, 흔한 오답을 막는 짧은 말 하나까지",
    "CAPTAIN": "쉬운 답 하나를 막는 말이 들어 있어 한 번 더 생각해야 하는 상황",
    "DETECTIVE": "단서 두 개를 함께 따져야 풀리는 상황",
}
REASON_MAX = {"LITTLE": 70, "CAPTAIN": 90, "DETECTIVE": 120}


# ---------------------------------------------------------------- numbers in words

_UNITS = {1: ("하나", "한"), 2: ("둘", "두"), 3: ("셋", "세"), 4: ("넷", "네"), 5: ("다섯",), 6: ("여섯",),
          7: ("일곱",), 8: ("여덟",), 9: ("아홉",)}
_TENS = {1: ("열",), 2: ("스물", "스무"), 3: ("서른",), 4: ("마흔",), 5: ("쉰",), 6: ("예순",), 7: ("일흔",),
         8: ("여든",), 9: ("아흔",)}

NUMBER_WORDS = {
    "ONE": 1, "TWO": 2, "THREE": 3, "FOUR": 4, "FIVE": 5, "SIX": 6, "SEVEN": 7, "EIGHT": 8, "NINE": 9, "TEN": 10,
    "ELEVEN": 11, "TWELVE": 12, "THIRTEEN": 13, "FOURTEEN": 14, "FIFTEEN": 15, "SIXTEEN": 16, "SEVENTEEN": 17,
    "EIGHTEEN": 18, "NINETEEN": 19, "TWENTY": 20,
}
ENGLISH_OF = {v: k for k, v in NUMBER_WORDS.items()}
# the topic particle after a number word as it sounds in Korean: one(원)은, two(투)는, seven(세븐)은, twelve(트웰브)는
NUMBER_TOPIC = {w: "는" if w in ("TWO", "THREE", "FOUR", "FIVE", "SIX", "TWELVE", "TWENTY") else "은" for w in NUMBER_WORDS}

# a word a child must not hear from the car, even as a letter-counting word
ENGLISH_BLOCK = {
    "KILL", "DIE", "DEAD", "DEATH", "GUN", "BOMB", "BLOOD", "HATE", "STUPID", "IDIOT", "DUMB", "UGLY", "FAT", "SEX",
    "SEXY", "NAKED", "DRUG", "BEER", "WINE", "DAMN", "HELL", "SHIT", "FUCK", "POOP", "FIRE", "KNIFE", "WAR",
}

# a letter-counting word is heard, not seen: it is a thing a child pictures at once (CAT, APPLE, TRAIN), at least
# this long, and never a little word that sounds like another or like a letter (ON, UP, SEA, YOU...) nor one of the
# words with nothing to picture an earlier bank used (AIR, HOT, SPEED...)
LETTER_WORD_MIN = 3
LETTER_WORD_NEVER = {
    "ON", "AN", "UN", "IN", "AT", "UP", "IT", "IS", "AS", "BE", "BY", "DO", "GO", "HE", "IF", "ME", "MY", "NO", "OF",
    "OH", "OK", "OR", "SO", "TO", "US", "WE", "THE", "AND", "BUT", "FOR", "NOT", "YES", "OFF", "OUT", "TOO", "ARE",
    "WAS", "HAS", "HIS", "HER", "ITS", "YOU", "WHO", "WHY", "HOW", "ALL", "ANY", "CAN", "WON", "ATE", "BEE", "SEA",
    "SEE", "EYE", "TEA", "PEA", "OWE", "WAY", "FAR",
    "AIR", "HOT", "COLD", "WARM", "WIND", "SPEED", "BREEZE", "COOLING", "LIGHT",
}

# counters that turn a number into "how many": 다섯 개, 세 칸, 두 단, 세 글자
_COUNTERS = r"\s*(개|칸|단|도|마리|명|송이|알|그루|장|권|글자|단계|층|살)"
# the answer said outright: "정답은 다섯", "답이 셋이야"
_SAID = r"(정답|답)\s*(은|는|이|이야|:)?\s*"


def native(n: int) -> list[str]:
    """Korean native number words for n (1~99): 5 -> 다섯, 12 -> 열둘/열두."""
    if not 1 <= n <= 99:
        return []
    tens, units = divmod(n, 10)
    if tens == 0:
        return list(_UNITS[units])
    if units == 0:
        return list(_TENS[tens])
    return [t + u for t in _TENS[tens] for u in _UNITS[units]]


def mentions(text: str, n: int) -> bool:
    """The number is written in the text, as digits or in Korean words (세 개, 다섯)."""
    clean = unicodedata.normalize("NFKC", text)
    if re.search(rf"(?<!\d){n}(?!\d)", clean):
        return True
    t = norm(clean)
    return any(w in t for w in native(n))


def leaks(text: str, n: int) -> bool:
    """The answer itself is in the text: as digits, or as Korean words counting something (다섯 개, 세 칸)."""
    clean = unicodedata.normalize("NFKC", text)
    if re.search(rf"(?<!\d){n}(?!\d)", clean):
        return True
    return any(re.search(r"(?<![가-힣])" + re.escape(w) + _COUNTERS, clean) or re.search(_SAID + re.escape(w), clean) for w in native(n))


# ---------------------------------------------------------------- working the answer out


class Wrong(ValueError):
    """The question does not work out, or does not fit the age."""


_OPS = {ast.Add: "+", ast.Sub: "-", ast.Mult: "*", ast.Div: "/"}


def solve_math(expr: str, level: Level) -> tuple[int, list[int]]:
    """The value of a sum made of whole numbers, + - × ÷ and brackets, and the numbers in it.

    Every step must stay a whole number from 0 up, and only the operations the age allows may be used.
    """
    text = expr.replace("×", "*").replace("÷", "/").replace("x", "*").strip()
    if not text or len(text) > EXPR_MAX:
        raise Wrong("식 없음")
    try:
        tree = ast.parse(text, mode="eval")
    except (SyntaxError, ValueError):  # ValueError: a null character in the text
        raise Wrong(f"식을 읽을 수 없음: {expr}") from None
    numbers: list[int] = []
    ops: list[str] = []

    def walk(node: ast.AST) -> int:
        if isinstance(node, ast.Constant) and type(node.value) is int:
            if node.value > level.max_operand:
                raise Wrong(f"수가 너무 큼: {node.value}")
            numbers.append(node.value)
            return node.value
        if isinstance(node, ast.BinOp) and type(node.op) in _OPS:
            a, b = walk(node.left), walk(node.right)
            op = _OPS[type(node.op)]
            if op not in level.ops:
                raise Wrong(f"이 나이에 쓰지 않는 셈: {op}")
            ops.append(op)
            # both sides of a × are small; a ÷ only needs a small divisor (24 ÷ 4 is fine for the oldest)
            if (op == "*" and (a > level.max_factor or b > level.max_factor)) or (op == "/" and b > level.max_factor):
                raise Wrong("곱하거나 나누는 수가 너무 큼")
            if op == "+":
                return a + b
            if op == "-":
                if a < b:
                    raise Wrong("중간에 0보다 작아짐")
                return a - b
            if op == "*":
                return a * b
            if b == 0 or a % b:
                raise Wrong("나누어떨어지지 않음")
            return a // b
        raise Wrong(f"식에 쓸 수 없는 것: {expr}")

    value = walk(tree.body)
    if not ops:
        raise Wrong("셈이 없음")
    if len(ops) > level.max_ops:
        raise Wrong(f"셈이 너무 많음: {len(ops)}번")
    return value, numbers


def solve_pattern(terms: list[int], level: Level) -> int:
    """The next number of a list that grows or shrinks by the same step (or, for the oldest, doubles or triples)."""
    if not 3 <= len(terms) <= 5:
        raise Wrong("수는 3~5개")
    if any(t < 0 or t > level.max_term for t in terms):
        raise Wrong("수가 너무 큼")
    steps = {b - a for a, b in zip(terms, terms[1:])}
    if len(steps) == 1:
        d = steps.pop()
        if d == 0 or abs(d) > level.step_max or (d < 0 and not level.step_down):
            raise Wrong(f"이 나이에 맞지 않는 규칙: {d:+d}씩")
        return terms[-1] + d
    if level.ratio and terms[0] > 0:
        for r in (2, 3):
            if all(b == a * r for a, b in zip(terms, terms[1:])):
                return terms[-1] * r
    raise Wrong("규칙이 하나로 정해지지 않음")


def solve_english(word: str, level: Level) -> int:
    """A number word is worth its number (FIVE = 5); any other word is worth its letters (CAT = 3)."""
    w = word.strip().upper()
    if not re.fullmatch(r"[A-Z]{2,8}", w):
        raise Wrong(f"영어 단어는 알파벳 2~8글자: {word}")
    if w in ENGLISH_BLOCK:
        raise Wrong(f"쓰면 안 되는 단어: {w}")
    if w in NUMBER_WORDS:
        if NUMBER_WORDS[w] > level.english_max:
            raise Wrong(f"이 나이에 어려운 숫자 단어: {w}")
        return NUMBER_WORDS[w]
    if len(w) < LETTER_WORD_MIN or w in LETTER_WORD_NEVER:
        raise Wrong(f"글자 수를 세기에 짧거나 헷갈리는 단어: {w}")
    if len(w) > level.letters[1]:
        raise Wrong(f"이 나이에 긴 단어: {w} (글자 수 {level.letters[1]}까지)")
    return len(w)


ENGLISH_SUM = re.compile(r"[A-Za-z]+(\s*[-+*]\s*[A-Za-z]+)+")


def solve_english_sum(expr: str, level: Level) -> tuple[int, list[str]]:
    """A sum said with English number words ("SEVEN-FIVE" for "seven 빼기 five"): its value and its words."""
    if not level.english_sums:
        raise Wrong("이 나이는 영어 숫자 셈을 하지 않음")
    if not ENGLISH_SUM.fullmatch(expr.strip()):
        raise Wrong(f"영어 숫자 셈은 숫자 영단어와 + - *로만: {expr}")
    words = [w.upper() for w in re.findall(r"[A-Za-z]+", expr)]
    for w in words:
        if w not in NUMBER_WORDS:
            raise Wrong(f"숫자 영단어가 아님: {w}")
        if NUMBER_WORDS[w] > level.english_max:
            raise Wrong(f"이 나이에 어려운 숫자 단어: {w}")
    digits = re.sub(r"[A-Za-z]+", lambda m: str(NUMBER_WORDS[m.group().upper()]), expr)
    value, _ = solve_math(digits, level)
    return value, words


def english_reach(age: str) -> int:
    """The largest answer an English question can have at this age: 4~6 say number words up to five and count
    three-letter words, so a fan step of 6~8 has no English question for them."""
    level = LEVELS[age]
    return max(level.english_max, level.letters[1])


def english_sum(question) -> bool:
    """An English question that is a sum of number words (its expr), not one word."""
    return bool(question.expr.strip())


def letter_count(word: str) -> bool:
    """An English question that counts its word's letters (any word but a number word)."""
    w = word.strip().upper()
    return bool(w) and w not in NUMBER_WORDS


def check_question(q: QuizKey, question, age: str) -> list[str]:
    """Problems with the question itself (the words around it are checked in checker.py). ``question`` is a
    DraftQuestion."""
    if question is None:
        return ["문제 없음"]
    level = LEVELS[age]
    problems: list[str] = []
    text = question.text.strip()
    if question.kind != q.kind:
        problems.append(f"문제 종류가 다름: {question.kind}")
    longest = REASON_MAX[age] if q.kind == "REASON" else QUESTION_MAX
    if not text:
        problems.append("문제 문장 없음")
    elif len(text) > longest:
        problems.append(f"문제 문장 길이 {len(text)} > {longest}")
    if problems:
        return problems
    try:
        if q.kind == "MATH":
            value, numbers = solve_math(question.expr, level)
            if q.value in numbers:
                problems.append("식에 정답 수가 들어 있음")
            problems += [f"식의 수가 문제에 없음: {n}" for n in numbers if not mentions(text, n)]
        elif q.kind == "PATTERN":
            value = solve_pattern(list(question.terms), level)
            problems += [f"수열의 수가 문제에 없음: {n}" for n in question.terms if not mentions(text, n)]
        elif q.kind == "ENGLISH" and english_sum(question):
            value, words = solve_english_sum(question.expr, level)
            if ENGLISH_OF.get(q.value) in words:
                problems.append("식에 정답 수가 들어 있음")
            problems += [f"식의 영단어가 문제에 없음: {w}" for w in words
                         if not re.search(rf"(?<![A-Za-z]){w}(?![A-Za-z])", text, re.IGNORECASE)]
        elif q.kind == "ENGLISH":
            value = solve_english(question.word, level)
            if question.word.strip().upper() not in text.upper():
                problems.append("영어 단어가 문제에 없음")
        else:
            # a situation asks for a function: nothing to work out, the bank checked its words when it was made
            value = q.value
    except Wrong as e:
        return [str(e)]
    if value != q.value:
        problems.append(f"답이 다름: {value} (정답 {q.value})")
    return problems


def english_word(q: QuizKey, question) -> str | None:
    """The English the question may say (only an English question has any): its one word, or for a sum of number
    words the sum ("SEVEN-FIVE")."""
    if q.kind != "ENGLISH" or question is None:
        return None
    return (question.expr if english_sum(question) else question.word).strip().upper()


def without_word(text: str, word: str | None) -> str:
    """The text without the question's English: its word, or every word of a sum of number words."""
    for w in re.findall(r"[A-Za-z]+", word or ""):
        text = re.sub(rf"(?<![A-Za-z]){w}(?![A-Za-z])", "", text, flags=re.IGNORECASE)
    return text


# a number word with no other letter next to it; a Korean particle may follow it directly ("ONE부터")
_NUMBER_WORD = re.compile(r"(?<![A-Za-z])(" + "|".join(NUMBER_WORDS) + r")(?![A-Za-z])", re.IGNORECASE)


def hint_without_english(hint: str, word: str | None) -> str:
    """A bank hint without the English it may say: an English question's one word ("WIND를 한 글자씩 짚어 봐") and
    counting in English ("ONE, TWO 하고 세어 봐"). Any other question's hint may say no English at all."""
    return _NUMBER_WORD.sub("", without_word(hint, word)) if word else hint


# ---------------------------------------------------------------- what the writer is told

# the trouble a scene is about, told to the writer only when the bank has no question for the key (the car then asks
# its own); the friend speaks as "나", and the cause is the story's, never the season or the light, which the car
# does not know
_TROUBLE = {
    "FAN": "바람이 필요한 까닭이 생긴다(예: 바람개비를 돌려야 한다)",
    "TEMP_UP": "차 안 공기를 조금 따뜻하게 해야 하는 까닭이 생긴다",
    "TEMP_DOWN": "차 안 공기를 조금 시원하게 해야 하는 까닭이 생긴다",
    "LIGHTS": "작은 불빛이 필요한 까닭이 생긴다",
    "SEAT_HEAT": "안전 지킴이 의자를 데워야 하는 까닭이 생긴다",
}

# a situation's trouble, by the function it needs
_REASON_TROUBLE = {
    ("AC", 1): "내가 덥다. 바람은 나오는데 시원하지 않다",
    ("AC", 0): "내가 덜덜 떨리는데, 더울 때 켜는 시원한 바람이 켜져 있다",
    ("RECIRC", 1): "바람 구멍으로 차 밖의 냄새가 들어온다",
    ("RECIRC", 0): "같은 공기만 빙글빙글 돌아서 숨이 답답하다",
    ("DEFROST", 1): "앞 유리 안쪽이 뿌예지려고 한다",
    ("TEMP_UP", 1): "내 몸이 으슬으슬 떨린다",
    ("TEMP_DOWN", 1): "에어컨이 켜져 있는데도 내가 아직 조금 덥다",
    ("FAN_UP", 1): "바람이 약해서 바람개비가 느릿느릿 돈다. 바람이 지금보다 더 필요하다",
    ("FAN_DOWN", 1): "바람이 너무 세서 종이가 날아가려 한다. 바람은 조금 나와야 한다",
    ("SEAT_HEAT_ON", 1): "안전 지킴이 의자만 차갑다",
    ("SEAT_HEAT_OFF", 0): "안전 지킴이 의자가 계속 데워져서 뜨끈뜨끈하다",
    ("LIGHT_CHILD", 1): "내가 준 그림 지도가 작아서 뒷자리가 더 밝아야 잘 보인다",
    ("LIGHTS_OFF", 0): "작은 불이 하나라도 켜져 있으면 마음이 안 놓인다",
}


def trouble(q: QuizKey) -> str:
    if q.kind == "REASON":
        return _REASON_TROUBLE[(q.dial, q.value)]
    return _TROUBLE[q.dial]


def what_the_answer_does(q: QuizKey) -> str:
    """What the app says after the question, so the writer knows what the answer will be used for."""
    if q.kind == "REASON":
        return f"아이가 상황을 듣고 알맞은 차 기능을 스스로 찾아 '{REASON_ACTIONS[(q.dial, q.value)]}'를 한다"
    return {
        "FAN": "답만큼 바람 세기(풍량)를 맞춘다",
        "TEMP_UP": "답만큼 온도를 올린다",
        "TEMP_DOWN": "답만큼 온도를 내린다",
        "LIGHTS": "켜진 독서등(자리 위 작은 불)이 모두 답의 수가 되게 한다",
        "SEAT_HEAT": "의자 열선을 답의 단계로 맞춘다",
    }[q.dial]


# words that would send the child the other way: a cooling scene that talks about warming up, and so on
_OTHER_WAY = {
    "TEMP_DOWN": ["따뜻하게", "데워", "따끈하게"],
    "TEMP_UP": ["시원하게", "식혀", "차갑게"],
    "FAN_UP": ["약하게", "줄여"],
    "FAN_DOWN": ["더 세게", "세게 해"],
    "SEAT_HEAT_ON": ["차갑게", "시원하게"],
    "SEAT_HEAT_OFF": ["더 따뜻하게", "더 데워"],
    "LIGHT_CHILD": ["눈부셔", "불을 꺼"],
    "LIGHTS_OFF": ["깜깜해서", "불을 켜"],
}


def other_way(q: QuizKey) -> list[str]:
    if q.dial == "AC":
        return ["추워", "꺼 줘", "끄자"] if q.value else ["더워", "켜 줘", "켜자"]
    if q.dial == "RECIRC":
        return ["답답", "맑은 공기"] if q.value else ["먼지", "매연"]
    return _OTHER_WAY.get(q.dial, [])


def scene_lines(q: QuizKey, question) -> list[str]:
    """The lines about one quiz scene in the writer's message. ``question`` is the DraftQuestion the bank gave this
    scene: the writer writes the story that leads to it and the line said once it is solved."""
    lines = [f"  이 장면의 문제 (정해져 있음, 친구가 speech 바로 뒤에 읽는다): «{question.text}»"]
    if q.kind == "REASON":
        lines.append(f"  정답 조작: {REASON_ACTIONS[(q.dial, q.value)]} (아이가 상황을 듣고 스스로 찾는다). "
                     "bubble, speech, art에는 버튼 이름과 할 일을 쓰지 않는다. result에서는 써도 된다")
        lines.append("  speech: 문제 문장의 상황이 생긴 까닭을, 친구가 목표를 위해 하던 일에서 만든다")
    else:
        lines.append(f"  정답: {q.value} ({what_the_answer_does(q)}). 이 수는 bubble, speech, art에 쓰지 않는다. "
                     "result에서는 써도 된다")
        lines.append("  speech: 이 장치가 이야기에서 왜 필요한지까지만 쓴다. 왜 그 수인지는 지어내지 않는다")
    if words := other_way(q):
        lines.append(f"  반대로 들리는 말은 bubble과 speech에 쓰지 않는다: {', '.join(words)}")
    return lines


def answer_words(q: QuizKey) -> list[str]:
    """Words a picture or the new friend must not show, because they would give the answer away. A situation has no
    number, and the button names are kept out of every picture anyway."""
    if q.kind == "REASON":
        return []
    return [str(q.value), *[w for w in native(q.value) if len(w) > 1]]
