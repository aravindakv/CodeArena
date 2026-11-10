import heapq


def solve(meetings):
    ends = []
    for s, e in sorted(meetings):
        if ends and ends[0] <= s:
            heapq.heapreplace(ends, e)
        else:
            heapq.heappush(ends, e)
    return len(ends)


def brute(meetings):
    points = sorted({p for m in meetings for p in m})
    return max(sum(1 for s, e in meetings if s <= t < e) for t in points)