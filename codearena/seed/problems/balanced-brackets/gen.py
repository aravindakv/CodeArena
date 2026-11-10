def gen(rng, size):
    n = {"edge": rng.randint(0, 3), "small": rng.randint(2, 40), "large": rng.randint(50000, 100000)}[size]
    if rng.random() < 0.5:                      # build a balanced string, maybe break it
        out, stack = [], []
        while len(out) + len(stack) < n:
            if stack and rng.random() < 0.5:
                out.append({"(": ")", "[": "]", "{": "}"}[stack.pop()])
            else:
                c = rng.choice("([{")
                stack.append(c)
                out.append(c)
        while stack:
            out.append({"(": ")", "[": "]", "{": "}"}[stack.pop()])
        if out and rng.random() < 0.4:
            k = rng.randrange(len(out))
            out[k] = rng.choice("()[]{}")
        return {"s": "".join(out)}
    return {"s": "".join(rng.choice("()[]{}") for _ in range(n))}