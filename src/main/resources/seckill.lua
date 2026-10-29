local get_stock=redis.call('GET',KEYS[1])   --KEYS1 库存KEY KEYS2 购买记录集合KEY  ARGV1 用户id
if(get_stock==false) then
    return 5
else
    local stock_value=tonumber(get_stock)
    if(stock_value==nil) then
        return 5
    else
        if(stock_value<=0) then
            return 1
        else
            local purchased=redis.call('SISMEMBER',KEYS[2],ARGV[1])--判断用户id是否存在于集合中
            if(purchased==1) then
                return 2
            else--没有购买，扣减库存，加入用户Id
                redis.call('DECR',KEYS[1])
                redis.call('SADD',KEYS[2],ARGV[1])
                return 0
    end


        end

    end

end
