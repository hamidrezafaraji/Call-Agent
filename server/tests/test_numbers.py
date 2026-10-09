import pytest

from callagent.numbers import find_numbers


def one(text):
    found = find_numbers(text)
    assert len(found) == 1, found
    e = found[0]
    assert text[e.start:e.end] == e.text
    return e


@pytest.mark.parametrize(
    "text, value",
    [
        # spoken groups with the leading zero
        ("شماره‌م صفر نهصد و دوازده چهارصد و پنجاه و یک بیست و دو سی و هفت هست", "09124512237"),
        # leading zero not spoken
        ("نهصد و دوازده چهارصد و پنجاه و یک بیست و پنج نود و هفت", "09124512597"),
        # glued words, as Whisper sometimes writes them
        ("صفر نهصدودوازده چهارصدوپنجاه‌ویک بیست‌وپنج نود و هفت", "09124512597"),
        # "نه صد" written as two words, plus Arabic yeh/kaf
        ("صفر نه صد و دوازده چهارصد و پنجاه و يك بيست و پنج نود و هفت", "09124512597"),
        # digits, Persian and Latin, with separators
        ("بنویس ۰۹۱۲ ۴۵۱ ۲۵ ۹۷ ممنون", "09124512597"),
        ("call me 0912-451-2597 please", "09124512597"),
        ("۰۹۱۲۴۵۱۲۵۹۷", "09124512597"),
        # mixed digits and words
        ("۰۹۱۲ چهارصد و پنجاه و یک بیست و پنج نود و هفت", "09124512597"),
        # landline with area code
        ("صفر بیست و یک هشتاد و هشت هشتاد و هشت هشتاد و دو بیست و سه", "02188888223"),
    ],
)
def test_phone_numbers(text, value):
    e = one(text)
    assert e.type == "phone"
    assert e.value == value


def test_exact_phone_is_marked_exact():
    assert one("صفر نهصد و دوازده چهارصد و پنجاه و یک بیست و پنج نود و هفت").exact is True


def test_unusual_grouping_keeps_original_text():
    # "۰۹۰-۴۵-۲۰۰-۴۵" read for a "round" look: 10 digits, flagged as not exact
    e = one("صفر نود چهل و پنج دویست چهل و پنج")
    assert e.type == "phone"
    assert e.value == "0904520045"
    assert e.exact is False
    assert e.text == "صفر نود چهل و پنج دویست چهل و پنج"


def test_card_number_in_digits():
    e = one("کارت 6037-9912-4560-1823 به نام من")
    assert (e.type, e.value, e.exact) == ("card", "6037991245601823", True)


def test_card_number_spoken_with_thousands():
    text = ("شش هزار و سی و هفت نه هزار و نهصد و دوازده "
            "چهار هزار و پانصد و شصت هزار و هشتصد و بیست و سه")
    e = one(text)
    assert (e.type, e.value) == ("card", "6037991245601823")


def test_two_numbers_back_to_back_are_split():
    text = "صفر نهصد و دوازده چهارصد و پنجاه و یک بیست و پنج نود و هفت صفر نهصد و سی و پنج یکصد و بیست سی چهل"
    assert [e.value for e in find_numbers(text)] == ["09124512597", "09351203040"]


def test_trailing_count_is_not_glued_onto_phone():
    # "دو" after the phone number belongs to "دو تا کارتن", and "تا" breaks the run
    text = "صفر نهصد و دوازده چهارصد و پنجاه و یک بیست و پنج نود و هفت دو تا کارتن"
    assert [e.value for e in find_numbers(text)] == ["09124512597"]


@pytest.mark.parametrize(
    "text",
    [
        "دویست و پنجاه هزار تومان",
        "سفارش ۲۰۰ کارتن چهل در شصت",
        "نه، فردا تماس می‌گیرم",
        "",
    ],
)
def test_no_false_positives(text):
    assert find_numbers(text) == []
