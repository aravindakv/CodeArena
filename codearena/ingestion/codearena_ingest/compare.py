"""Output comparison, identical in meaning to judge-worker's Comparators.java."""
import json


def _same(e, a) -> bool:
    if isinstance(e, bool) or isinstance(a, bool):
        return e is a or e == a and type(e) is type(a)
    if isinstance(e, (int, float)) and isinstance(a, (int, float)):
        return e == a
    if isinstance(e, list) and isinstance(a, list):
        return len(e) == len(a) and all(_same(x, y) for x, y in zip(e, a))
    if isinstance(e, dict) and isinstance(a, dict):
        return e.keys() == a.keys() and all(_same(e[k], a[k]) for k in e)
    return e == a


def _close(e, a) -> bool:
    if isinstance(e, (int, float)) and isinstance(a, (int, float)) and not isinstance(e, bool):
        d = abs(e - a)
        return d <= 1e-6 or d <= 1e-6 * max(abs(e), abs(a))
    if isinstance(e, list) and isinstance(a, list):
        return len(e) == len(a) and all(_close(x, y) for x, y in zip(e, a))
    return _same(e, a)


def equal(mode: str, expected: str, actual: str) -> bool:
    if mode == "EXACT":
        return expected.strip() == actual.strip()
    try:
        e, a = json.loads(expected), json.loads(actual)
    except (ValueError, TypeError):
        return False
    if mode == "UNORDERED_ARRAY" and isinstance(e, list) and isinstance(a, list):
        key = lambda x: json.dumps(x, sort_keys=True)
        return len(e) == len(a) and _same(sorted(e, key=key), sorted(a, key=key))
    if mode == "FLOAT_1E6":
        return _close(e, a)
    return _same(e, a)