-- Checks one login code (CU-1) in one atomic step, so two guesses sent at the same moment can't both
-- slip past the attempt limit.
--
-- KEYS[1] = otp:{msisdn}     hash: hash (HMAC of msisdn:code), attempts (wrong codes so far)
-- ARGV[1] = HMAC of the code the customer typed
-- ARGV[2] = most wrong codes allowed
--
-- Returns OK (right code; the code is deleted, so it works once), WRONG, LOCKED (too many wrong codes;
-- the code is deleted) or MISSING (no code, or it expired).

local stored = redis.call('HGET', KEYS[1], 'hash')
if not stored then
    return 'MISSING'
end

if stored == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 'OK'
end

local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
if attempts >= tonumber(ARGV[2]) then
    redis.call('DEL', KEYS[1])
    return 'LOCKED'
end
return 'WRONG'
