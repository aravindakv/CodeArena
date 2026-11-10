def gen(rng, size):
    r, c = {"edge": (rng.randint(1, 2), rng.randint(1, 3)),
            "small": (rng.randint(2, 8), rng.randint(2, 8)),
            "large": (rng.randint(30, 40), rng.randint(30, 40))}[size]
    p = rng.choice([0.2, 0.35, 0.5])
    grid = [[1 if rng.random() < p else 0 for _ in range(c)] for _ in range(r)]
    grid[0][0] = 0
    grid[r - 1][c - 1] = 0
    return {"grid": grid, "k": rng.randint(0, max(0, (r + c) // 3))}