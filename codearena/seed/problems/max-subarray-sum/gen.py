def gen(rng, size):
    n = {"edge": rng.randint(1, 3), "small": rng.randint(4, 60), "large": rng.randint(100000, 200000)}[size]
    lim = 10**9 if rng.random() < 0.5 else 100
    return {"nums": [rng.randint(-lim, lim) for _ in range(n)]}