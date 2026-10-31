-- RL-3: one subscriber's data use in a sliding window (e.g. the last 15 minutes), in one atomic step.
-- Each report is a member of a sorted set, scored with the time it arrived, so old reports simply fall
-- out of the window; there is no fixed bucket edge where a burst could hide.
--
-- KEYS[1] = usage:{msisdn}   sorted set: member "<id>:<mb>", score = arrival time (ms)
-- ARGV[1] = MB used now (0 = only read the total)
-- ARGV[2] = window length (ms)
-- ARGV[3] = a unique id for this report (so two reports of the same size are two members)
--
-- Returns the MB used in the window, this report included. Uses Redis' clock, so every backend copy
-- agrees on "now".

local used = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

if used > 0 then
    redis.call('ZADD', KEYS[1], now, ARGV[3] .. ':' .. used)
end
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. (now - window))

local total = 0
for _, member in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
    total = total + tonumber(string.match(member, ':(%d+)$'))
end

-- nothing in the window any more: the key isn't needed (RD-8)
if total > 0 then
    redis.call('PEXPIRE', KEYS[1], window)
end
return total
