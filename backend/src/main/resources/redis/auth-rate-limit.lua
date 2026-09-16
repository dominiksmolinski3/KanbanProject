-- The escalation for one (rule, dimension, key) triple, scored and rewritten atomically. Redis runs
-- a script to completion before serving anything else on the keyspace, which is this migration's
-- equivalent of the outbox's FOR UPDATE SKIP LOCKED and the deadline sweep's claim: the read, the
-- decision and the write happen as one unit, so two replicas hitting the same key at once can never
-- interleave into a double-allow the way two Caffeine caches on two pods silently did.
--
-- KEYS[1] = the escalation key
-- ARGV[1] = now, epoch milliseconds - supplied by the caller rather than read from Redis's own
--           clock, so every replica scores the same key against the same reading (ordinary NTP
--           drift across a handful of pods is milliseconds against cooldowns measured in tens of
--           seconds) and so a test can drive the clock without waiting a cooldown out.
-- ARGV[2] = free attempts before the first cooldown
-- ARGV[3] = base cooldown, milliseconds
-- ARGV[4] = max cooldown (the ceiling), milliseconds
-- ARGV[5] = window - a key this quiet for this long is forgiven and starts its burst over
--
-- Returns {allowed (1/0), wait_ms}. wait_ms is 0 when allowed.

local now = tonumber(ARGV[1])
local free_attempts = tonumber(ARGV[2])
local base_cooldown = tonumber(ARGV[3])
local max_cooldown = tonumber(ARGV[4])
local window = tonumber(ARGV[5])

local data = redis.call('HMGET', KEYS[1], 'attempts', 'nextAllowedAt', 'lastSeenAt')
local attempts = tonumber(data[1])
local next_allowed_at = tonumber(data[2])
local last_seen_at = tonumber(data[3])
local seen = last_seen_at ~= nil

-- A first sighting is initialised rather than compared against, and so is a key old enough to have
-- aged out of its own window - the two look identical from here, and both start the burst over.
if not seen or (now - last_seen_at) >= window then
  attempts = 0
  next_allowed_at = now
end

-- Refused attempts count as activity too: this write happens whether or not the attempt below is
-- let through, which is what keeps a hammered key from ageing out of its own escalation - the quiet
-- that forgives one has to be actual quiet.
last_seen_at = now

if now < next_allowed_at then
  redis.call('HSET', KEYS[1], 'attempts', attempts, 'nextAllowedAt', next_allowed_at, 'lastSeenAt', last_seen_at)
  redis.call('PEXPIRE', KEYS[1], window)
  return {0, next_allowed_at - now}
end

-- Escalation is charged on the way out: this attempt is allowed at the cooldown it walked in with,
-- and only the next one pays for it - so hammering a key already past its wait neither extends the
-- next cooldown nor shortens it.
attempts = attempts + 1
local spent = attempts - free_attempts + 1
local cooldown = 0
if spent >= 1 then
  -- Doublings are capped the same way the Java implementation capped its bit shift: an attacker
  -- who never lets the key go quiet can push `attempts` arbitrarily high, and this loop must stay
  -- bounded regardless, since Redis runs it to completion on a single thread before anything else.
  local doublings = spent - 1
  if doublings > 32 then
    doublings = 32
  end
  cooldown = base_cooldown
  for _ = 1, doublings do
    cooldown = cooldown * 2
    if cooldown >= max_cooldown then
      break
    end
  end
  if cooldown > max_cooldown then
    cooldown = max_cooldown
  end
end

next_allowed_at = now + cooldown
redis.call('HSET', KEYS[1], 'attempts', attempts, 'nextAllowedAt', next_allowed_at, 'lastSeenAt', last_seen_at)
redis.call('PEXPIRE', KEYS[1], window)
return {1, 0}
