def solve(nums, target):
    first = {}
    for j, x in enumerate(nums):
        if target - x in first:
            return [first[target - x], j]
        first.setdefault(x, j)
    raise ValueError("no pair")


def brute(nums, target):
    for j in range(len(nums)):
        for i in range(j):
            if nums[i] + nums[j] == target:
                return [i, j]