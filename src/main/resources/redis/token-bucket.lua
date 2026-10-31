-- Token bucket for one gateway, in one atomic step (RD-3): refill, take a token, save.
-- Two backend copies can never both take the last token, and it is one round trip.
--
-- KEYS[1] = ratelimit:{gateway}   hash: tokens (may be a fraction), updatedAt (ms)
-- ARGV[1] = capacity              most tokens the bucket holds (the allowed burst)
-- ARGV[2] = refill per ms         tokens added per millisecond (the steady rate)
--
-- Returns { allowed (1/0), wait ms until one token is back (0 when allowed) }.
-- Uses Redis' own clock, so every backend copy agrees on "now".

local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'updatedAt')
local tokens = tonumber(state[1]) or capacity
local updatedAt = tonumber(state[2]) or now

-- lazy refill: add what came in since the last request, never above capacity
tokens = math.min(capacity, tokens + math.max(0, now - updatedAt) * refill)

local allowed = 0
local waitMs = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    waitMs = math.ceil((1 - tokens) / refill)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'updatedAt', tostring(now))
-- an idle bucket is full again after capacity / refill ms; then the key is not needed (RD-8)
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill) + 1000)

return { allowed, waitMs }
