-- One account's token bucket for the authenticated API, refilled and spent atomically. Redis runs a
-- script to completion before serving anything else on the keyspace, so two replicas taking a token
-- for the same account at once can never both take the last one - the reason this is a script and
-- not a GET and a SET from Java, and the same reason auth-rate-limit.lua is.
--
-- KEYS[1] = the account's bucket
-- ARGV[1] = now, epoch milliseconds - supplied by the caller, as auth-rate-limit.lua's is, so every
--           replica refills against the same kind of reading and a test can drive the clock.
-- ARGV[2] = refill rate, tokens per second
-- ARGV[3] = capacity (the burst)
--
-- Returns {allowed (1/0), wait_ms}. wait_ms is how long until one token is available again, 0 when
-- allowed. A refused request takes no token, so hammering does not dig the hole deeper; it simply
-- keeps being refused until the bucket refills.

local now = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local capacity = tonumber(ARGV[3])

local state = redis.call('HMGET', KEYS[1], 'tokens', 'at')
local tokens = tonumber(state[1])
local at = tonumber(state[2])

if tokens == nil or at == nil then
  tokens = capacity
  at = now
end

-- A replica whose clock is behind the one that last wrote leaves the stamp alone rather than
-- winding it back, which would refill the same interval twice.
if now > at then
  tokens = math.min(capacity, tokens + (now - at) * rate / 1000)
  at = now
end

local allowed = 0
local wait = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  wait = math.ceil((1 - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'at', tostring(at))
-- Gone once it would have refilled to full anyway: an idle account's bucket costs nothing.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / rate) + 1000)

return {allowed, wait}
