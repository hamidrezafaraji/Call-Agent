"""Find phone and bank-card numbers in a Persian transcript.

Persians read numbers in groups ("صفر نهصد و دوازده، چهارصد و پنجاه و یک، بیست و پنج، نود و هفت"),
and Whisper may write them as words, as digits, or mixed. We parse each spoken group into its
digits, join adjacent groups, and keep runs that look like a phone number (starts with 0 or 9,
about 11 digits) or a card number (about 16 digits). The transcript itself is never changed;
we only return character spans plus the digits, so the UI can show the original words with a
"copy number" bubble on top.

Run directly for a quick check:  python -m callagent.numbers "متن"
"""
import re
from dataclasses import asdict, dataclass

UNITS = {"صفر": 0, "یک": 1, "دو": 2, "سه": 3, "چهار": 4, "پنج": 5, "شش": 6, "شیش": 6,
         "هفت": 7, "هشت": 8, "نه": 9}
TEENS = {"ده": 10, "یازده": 11, "دوازده": 12, "سیزده": 13, "چهارده": 14, "پانزده": 15,
         "پونزده": 15, "شانزده": 16, "شونزده": 16, "هفده": 17, "هیفده": 17, "هجده": 18,
         "هیجده": 18, "هژده": 18, "نوزده": 19}
TENS = {"بیست": 20, "سی": 30, "چهل": 40, "پنجاه": 50, "شصت": 60, "شست": 60, "هفتاد": 70,
        "هشتاد": 80, "نود": 90}
HUNDREDS = {"صد": 100, "یکصد": 100, "دویست": 200, "سیصد": 300, "چهارصد": 400, "پانصد": 500,
            "پونصد": 500, "ششصد": 600, "شیشصد": 600, "هفتصد": 700, "هشتصد": 800, "نهصد": 900}
THOUSAND = "هزار"
AND = "و"

# rank: a group is built from parts of strictly decreasing rank, e.g. 400 + 50 + 1
LEXICON: dict[str, tuple[int, int]] = {}
LEXICON.update({w: (v, 1) for w, v in UNITS.items()})
LEXICON.update({w: (v, 1) for w, v in TEENS.items()})
LEXICON.update({w: (v, 2) for w, v in TENS.items()})
LEXICON.update({w: (v, 3) for w, v in HUNDREDS.items()})
LEXICON[THOUSAND] = (1000, 4)
_SEGMENT_WORDS = sorted([*LEXICON, AND], key=len, reverse=True)

_DIGIT_MAP = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")
_LETTER_MAP = str.maketrans({"ي": "ی", "ى": "ی", "ك": "ک", "‌": None, "‏": None})

_TOKEN_RE = re.compile(
    r"(?P<digits>[0-9۰-۹٠-٩]+)"
    r"|(?P<word>[؀-ۿ‌]+)"
    r"|(?P<sep>[\s\-–—_,،]+)"
    r"|(?P<other>.)",
    re.S,
)

PHONE_LEN = 11  # 0912 451 25 97
CARD_LEN = 16   # 6037 9912 4560 1823


@dataclass
class Entity:
    type: str    # "phone" | "card"
    start: int   # character span in the transcript
    end: int
    text: str    # transcript[start:end], exactly as spoken
    value: str   # the digits, e.g. "09124512597"
    exact: bool  # False when the digit count is off by one: double-check against `text`


@dataclass
class _Tok:
    kind: str    # "num" | "and" | "digits" | "sep" | "other"
    start: int
    end: int
    value: int = 0
    rank: int = 0
    word: str = ""
    digits: str = ""


def _segment(word: str) -> list[str] | None:
    """Split a glued word like "چهارصدوپنجاه‌ویک" into number words and "و"."""
    if not word:
        return []
    for w in _SEGMENT_WORDS:
        if word.startswith(w):
            rest = _segment(word[len(w):])
            if rest is not None:
                return [w, *rest]
    return None


def _tokenize(text: str) -> list[_Tok]:
    toks: list[_Tok] = []
    for m in _TOKEN_RE.finditer(text):
        kind, s, e = m.lastgroup, m.start(), m.end()
        if kind == "digits":
            toks.append(_Tok("digits", s, e, digits=m.group().translate(_DIGIT_MAP)))
        elif kind == "word":
            parts = _segment(m.group().translate(_LETTER_MAP))
            if parts is None or all(p == AND for p in parts):
                toks.append(_Tok("and" if parts else "other", s, e))
                continue
            # glued pieces share the whole word's span
            for p in parts:
                if p == AND:
                    toks.append(_Tok("and", s, e))
                else:
                    v, r = LEXICON[p]
                    toks.append(_Tok("num", s, e, value=v, rank=r, word=p))
        elif kind == "sep" and "\n" not in m.group():
            toks.append(_Tok("sep", s, e))
        else:
            toks.append(_Tok("other", s, e))
    return toks


@dataclass
class _Group:
    digits: str
    start: int
    end: int
    next_i: int  # token index after the group


def _parse_word_group(toks: list[_Tok], i: int) -> _Group:
    """Parse one spoken number starting at toks[i] (a num token)."""
    first = toks[i]
    start, end = first.start, first.end
    if first.word == "صفر":
        return _Group("0", start, end, i + 1)

    thousands, value, last_rank = 0, 0, 5
    if first.rank == 4:  # bare "هزار" = 1000
        thousands, last_rank = 1000, 4
    else:
        value, last_rank = first.value, first.rank
    j = i + 1

    while True:
        k = _next_non_space(toks, j)
        if k >= len(toks):
            break
        t = toks[k]
        # "نه صد" -> 900 (unit directly followed by "صد")
        if t.kind == "num" and t.word == "صد" and last_rank == 1 and 1 <= value <= 9:
            value, last_rank, end, j = value * 100, 3, t.end, k + 1
            continue
        # "چهار هزار" -> multiply what we have so far
        if t.kind == "num" and t.rank == 4 and thousands == 0 and value:
            thousands, value, last_rank, end, j = value * 1000, 0, 4, t.end, k + 1
            continue
        # "... و پنجاه" -> add a smaller part joined by "و"
        if t.kind == "and":
            k2 = _next_non_space(toks, k + 1)
            if k2 < len(toks):
                n = toks[k2]
                ok = (n.kind == "num" and n.word != "صفر" and n.rank < last_rank
                      and not (last_rank == 2 and n.value >= 10))
                if ok:
                    value += n.value
                    last_rank, end, j = n.rank, n.end, k2 + 1
                    continue
        break

    return _Group(str(thousands + value), start, end, j)


def _next_non_space(toks: list[_Tok], j: int) -> int:
    while j < len(toks) and toks[j].kind == "sep" and toks[j].word == "space":
        j += 1
    return j


def _groups_and_runs(text: str) -> list[list[_Group]]:
    toks = _tokenize(text)
    # mark whitespace-only separators: allowed inside a spoken number
    for t in toks:
        if t.kind == "sep" and not text[t.start:t.end].strip():
            t.word = "space"

    runs: list[list[_Group]] = []
    run: list[_Group] = []
    i = 0
    while i < len(toks):
        t = toks[i]
        if t.kind == "num":
            g = _parse_word_group(toks, i)
        elif t.kind == "digits":
            g = _Group(t.digits, t.start, t.end, i + 1)
        else:
            if t.kind != "sep" and run:  # words, "و" or punctuation end the run
                runs.append(run)
                run = []
            i += 1
            continue
        run.append(g)
        i = g.next_i
    if run:
        runs.append(run)
    return runs


def _target(digits: str) -> tuple[str, int] | None:
    if digits.startswith("0"):
        return "phone", PHONE_LEN
    if digits.startswith("9"):
        return "phone", PHONE_LEN - 1  # leading zero not spoken: "نهصد و دوازده ..."
    return "card", CARD_LEN


def _entities_in_run(text: str, run: list[_Group]) -> list[Entity]:
    found: list[Entity] = []
    i = 0
    while i < len(run):
        joined = "".join(g.digits for g in run[i:])
        kind, target = _target(joined)
        # Prefer an exact cut at a group boundary (two numbers said back to back).
        total, cut = 0, None
        for k in range(i, len(run)):
            total += len(run[k].digits)
            if total == target:
                cut, exact = k, True
                break
            if total > target:
                break
        if cut is None and abs(len(joined) - target) <= 1:
            cut, exact = len(run) - 1, False
        if cut is None:
            i += 1
            continue
        digits = "".join(g.digits for g in run[i:cut + 1])
        if kind == "phone" and digits.startswith("9"):
            digits = "0" + digits
        s, e = run[i].start, run[cut].end
        found.append(Entity(kind, s, e, text[s:e], digits, exact))
        i = cut + 1
    return found


def find_numbers(text: str) -> list[Entity]:
    if not text:
        return []
    out: list[Entity] = []
    for run in _groups_and_runs(text):
        out.extend(_entities_in_run(text, run))
    return out


if __name__ == "__main__":
    import json
    import sys

    sample = " ".join(sys.argv[1:])
    print(json.dumps([asdict(e) for e in find_numbers(sample)], ensure_ascii=False, indent=2))
