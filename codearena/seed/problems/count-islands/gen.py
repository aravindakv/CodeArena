def gen(rng, size):
    r, c = {"edge": (rng.randint(1, 2), rng.randint(1, 2)),
            "small": (rng.randint(2, 12), rng.randint(2, 12)),
            "large": (rng.randint(200, 300), rng.randint(200, 300))}[size]
    p = rng.choice([0.2, 0.45, 0.6])
    return {"grid": [[1 if rng.random() < p else 0 for _ in range(c)] for _ in range(r)]}