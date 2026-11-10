-- Atomically applies one judged contest submission to the leaderboard (ICPC rules).
-- KEYS[1] = lb:{cid}                 sorted set  member=userId  score=solved*1e6 - penaltyMinutes
-- KEYS[2] = lbu:{cid}:<uid>          hash        att:<slug> = wrong attempts, ac:<slug> = minute solved
-- KEYS[3] = lb:done:{cid}:<sid>      idempotency marker (SQS may deliver an event twice)
-- ARGV: 1 userId, 2 slug, 3 accepted (1/0), 4 minutesFromStart, 5 ttlSeconds, 6 penaltyPerWrongAttempt
-- All keys share the {cid} hash tag, so on Redis Cluster they live on one slot (required for EVAL).
if redis.call('SET', KEYS[3], '1', 'NX', 'EX', 86400) == false then
  return 0                                        -- duplicate event
end
if redis.call('HEXISTS', KEYS[2], 'ac:' .. ARGV[2]) == 1 then
  return 0                                        -- already solved: later submissions do not count
end
if ARGV[3] == '0' then
  redis.call('HINCRBY', KEYS[2], 'att:' .. ARGV[2], 1)
  redis.call('ZINCRBY', KEYS[1], 0, ARGV[1])      -- make sure the user appears on the board
else
  local wrong = tonumber(redis.call('HGET', KEYS[2], 'att:' .. ARGV[2]) or '0')
  local penalty = tonumber(ARGV[4]) + tonumber(ARGV[6]) * wrong
  redis.call('HSET', KEYS[2], 'ac:' .. ARGV[2], ARGV[4])
  redis.call('ZINCRBY', KEYS[1], 1000000 - penalty, ARGV[1])
end
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[5]))
return 1