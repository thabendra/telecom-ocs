-- One round trip for an Idempotency-Key (runs atomically inside Redis).
--   hit : the key exists        -> return its stored JSON (IN_PROGRESS lock or DONE result)
--   miss: the key doesn't exist -> store the IN_PROGRESS lock with its TTL and return false
--
-- KEYS[1] = idem:{clientId}:{key}
-- ARGV[1] = IN_PROGRESS record JSON
-- ARGV[2] = lock TTL in milliseconds

local existing = redis.call('GET', KEYS[1])
if existing then
    return existing
end

redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
return false
