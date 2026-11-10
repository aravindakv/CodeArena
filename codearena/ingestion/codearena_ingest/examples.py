import json


def split_top_level(s: str) -> list[str]:
    """Split on commas outside brackets and quotes (same rule as problem-service's ExampleInputs)."""
    out, depth, in_str, cur = [], 0, False, []
    for i, c in enumerate(s):
        if c == '"' and (i == 0 or s[i - 1] != "\\"):
            in_str = not in_str
        if not in_str:
            if c in "[{":
                depth += 1
            elif c in "]}":
                depth -= 1
            elif c == "," and depth == 0:
                out.append("".join(cur))
                cur = []
                continue
        cur.append(c)
    if "".join(cur).strip():
        out.append("".join(cur))
    return out


def example_to_input(text: str, param_names: list[str]) -> dict:
    """'nums = [1,2], target = 3' -> {"nums": [1, 2], "target": 3}"""
    parts = split_top_level(text)
    if len(parts) != len(param_names):
        raise ValueError(f"example input has {len(parts)} values, signature has {len(param_names)}: {text}")
    out = {}
    for part, name in zip(parts, param_names):
        key, eq, value = part.partition("=")
        if eq and key.strip() != name:
            raise ValueError(f"example names '{key.strip()}' where the signature has '{name}'")
        out[name] = json.loads(value.strip() if eq else part.strip())
    return out