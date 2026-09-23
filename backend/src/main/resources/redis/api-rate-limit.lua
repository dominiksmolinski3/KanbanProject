-- ARGV: now (epoch ms), refill tokens/s, capacity. Returns {allowed 1/0, wait_ms}; a refusal takes no token.
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
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / rate) + 1000)

return {allowed, wait}
