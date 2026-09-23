-- ARGV: now (epoch ms), free attempts, base cooldown ms, max cooldown ms, window ms. Returns {allowed 1/0, wait_ms}.
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

if not seen or (now - last_seen_at) >= window then
  attempts = 0
  next_allowed_at = now
end

last_seen_at = now

if now < next_allowed_at then
  redis.call('HSET', KEYS[1], 'attempts', attempts, 'nextAllowedAt', next_allowed_at, 'lastSeenAt', last_seen_at)
  redis.call('PEXPIRE', KEYS[1], window)
  return {0, next_allowed_at - now}
end

attempts = attempts + 1
local spent = attempts - free_attempts + 1
local cooldown = 0
if spent >= 1 then
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
