def gen(rng, size):
    n = {"edge": rng.randint(2, 4), "small": rng.randint(5, 50), "large": rng.randint(20000, 100000)}[size]
    lim = 10 if size == "edge" else 10**9
    nums = [rng.randint(-lim, lim) for _ in range(n)]
    i, j = sorted(rng.sample(range(n), 2))
    return {"nums": nums, "target": nums[i] + nums[j]}