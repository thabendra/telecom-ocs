-- CA-4: frees a reload lock only if it is still ours. Ours may have expired during a slow reload, and
-- then this key belongs to another request.
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
