def gen(rng, size):
    n = {"edge": rng.randint(1, 3), "small": rng.randint(4, 40), "large": rng.randint(50000, 100000)}[size]
    span = 50 if size != "large" else 10**9
    out = []
    for _ in range(n):
        s = rng.randint(0, span - 1)
        out.append([s, rng.randint(s + 1, min(span, s + max(2, span // 5)))])
    return {"meetings": out}