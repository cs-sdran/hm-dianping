--KEY1 库存KEY KEY2 订单KEY  ARGV1 用户ID
local exist=redis.call('SISMEMBER',KEYS[2],ARGV[1])
if(exist==0) then
    return 1
else
    local stock=redis.call('GET',KEYS[1])
    if(stock==false) then
        return 5
    else
        redis.call('INCR',KEYS[1])
        redis.call('SREM',KEYS[2],ARGV[1])
        return 0
    end
end