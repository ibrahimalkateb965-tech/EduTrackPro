"""Arabic currency wording."""

from decimal import Decimal, ROUND_HALF_UP


_ONES = ("", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة")
_TEENS = ("عشرة", "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر", "خمسة عشر", "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر")
_TENS = ("", "", "عشرون", "ثلاثون", "أربعون", "خمسون", "ستون", "سبعون", "ثمانون", "تسعون")
_HUNDREDS = ("", "مائة", "مائتان", "ثلاثمائة", "أربعمائة", "خمسمائة", "ستمائة", "سبعمائة", "ثمانمائة", "تسعمائة")


def _under_thousand(value: int) -> str:
    parts: list[str] = []
    if value >= 100:
        parts.append(_HUNDREDS[value // 100])
        value %= 100
    if value >= 20:
        unit = value % 10
        if unit:
            parts.append(_ONES[unit])
        parts.append(_TENS[value // 10])
    elif value >= 10:
        parts.append(_TEENS[value - 10])
    elif value:
        parts.append(_ONES[value])
    return " و".join(parts)


def _counted_form(value: int, singular: str, plural: str, accusative: str) -> str:
    """Arabic counted-noun agreement: 3-10 plural, 11-99 (mod 100) accusative singular, else singular."""
    if 3 <= value <= 10:
        return plural
    if 11 <= value % 100 <= 99:
        return accusative
    return singular


def _group(value: int, singular: str, dual: str, plural: str) -> str:
    if value == 1:
        return singular
    if value == 2:
        return dual
    words = _under_thousand(value)
    return f"{words} {_counted_form(value, singular, plural, singular)}"


def _number(value: int) -> str:
    if value == 0:
        return "صفر"
    parts: list[str] = []
    millions, value = divmod(value, 1_000_000)
    thousands, rest = divmod(value, 1_000)
    if millions:
        parts.append(_group(millions, "مليون", "مليونان", "ملايين"))
    if thousands:
        parts.append(_group(thousands, "ألف", "ألفان", "آلاف"))
    if rest:
        parts.append(_under_thousand(rest))
    return " و".join(parts)


def _currency(value: int, singular: str, dual: str, plural: str, accusative: str) -> str:
    if value == 0:
        return ""
    if value == 1:
        return singular
    if value == 2:
        return dual
    return f"{_number(value)} {_counted_form(value, singular, plural, accusative)}"


def arabic_amount_words(amount: Decimal) -> str:
    """Return Arabic words for an amount from zero through 999,999,999.99."""
    value = Decimal(amount).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    if value < 0 or value > Decimal("999999999.99"):
        raise ValueError("amount outside supported range")
    total = int(value * 100)
    riyals, halalas = divmod(total, 100)
    parts = [_currency(riyals, "ريال", "ريالان", "ريالات", "ريالاً")]
    if halalas:
        parts.append(_currency(halalas, "هللة", "هللتان", "هللات", "هللة"))
    words = " و".join(part for part in parts if part)
    return f"{words or 'صفر ريال'} فقط لا غير"
