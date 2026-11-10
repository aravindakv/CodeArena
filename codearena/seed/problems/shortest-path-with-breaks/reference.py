from collections import deque


def solve(grid, k):
    rows, cols = len(grid), len(grid[0])
    if rows == 1 and cols == 1:
        return 0
    k = min(k, rows + cols - 3) if rows + cols > 3 else k
    best = [[-1] * cols for _ in range(rows)]      # most walls left when reaching a cell
    best[0][0] = k
    q = deque([(0, 0, k, 0)])
    while q:
        r, c, left, d = q.popleft()
        for nr, nc in ((r + 1, c), (r - 1, c), (r, c + 1), (r, c - 1)):
            if 0 <= nr < rows and 0 <= nc < cols:
                nl = left - grid[nr][nc]
                if nl < 0 or nl <= best[nr][nc]:
                    continue
                if nr == rows - 1 and nc == cols - 1:
                    return d + 1
                best[nr][nc] = nl
                q.append((nr, nc, nl, d + 1))
    return -1


def brute(grid, k):
    rows, cols = len(grid), len(grid[0])
    seen = {(0, 0, 0)}
    q = deque([(0, 0, 0, 0)])
    while q:
        r, c, used, d = q.popleft()
        if (r, c) == (rows - 1, cols - 1):
            return d
        for nr, nc in ((r + 1, c), (r - 1, c), (r, c + 1), (r, c - 1)):
            if 0 <= nr < rows and 0 <= nc < cols:
                nu = used + grid[nr][nc]
                if nu <= k and (nr, nc, nu) not in seen:
                    seen.add((nr, nc, nu))
                    q.append((nr, nc, nu, d + 1))
    return -1