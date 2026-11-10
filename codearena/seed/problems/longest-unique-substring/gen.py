import string


def gen(rng, size):
    n = {"edge": rng.randint(0, 3), "small": rng.randint(4, 60), "large": rng.randint(50000, 100000)}[size]
    alphabet = string.ascii_lowercase[: rng.choice([2, 5, 26])]
    return {"s": "".join(rng.choice(alphabet) for _ in range(n))}