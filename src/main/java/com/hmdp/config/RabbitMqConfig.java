package com.hmdp.config;


import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String ORDER_EXCHANGE = "seckill.order.exchange";
    public static final String ORDER_QUEUE = "seckill.order.queue";
    public static final String ORDER_ROUTING_KEY = "seckill.order";

    @Bean
    public DirectExchange seckillOrderExchange(){
        DirectExchange directExchange = new DirectExchange(ORDER_EXCHANGE, true, false);
        return directExchange;
    }

    @Bean
    public Queue seckillOrderQueue(){
        Queue seckillorderqueue = new Queue(ORDER_QUEUE, true, false, false);
        return seckillorderqueue;
    }

    @Bean
    public Binding binding(@Qualifier("seckillOrderExchange") DirectExchange directExchange,
                           @Qualifier("seckillOrderQueue") Queue queue){
        Binding binding = BindingBuilder.bind(queue).to(directExchange).with(ORDER_ROUTING_KEY);
        return binding;

    }

    // 死信交换机：接收后续被转入失败处理流程的订单消息。
    @Bean
    public DirectExchange seckillOrderDeadExchange() {

        return new DirectExchange("seckill.order.dlx", true, false);
    }

    // 死信队列暂不配置消费者，保留消息供后续核对。
    @Bean
    public Queue seckillOrderDeadQueue() {

        return new Queue("seckill.order.dlq", true, false, false);
    }

    @Bean
    public Binding seckillOrderDeadBinding(
            @Qualifier("seckillOrderDeadExchange") DirectExchange exchange,
            @Qualifier("seckillOrderDeadQueue") Queue queue) {
        return BindingBuilder.bind(queue).to(exchange).with("seckill.order.failed");
    }

    @Bean
    public MessageConverter rabbitMessageConverter(){
        return new Jackson2JsonMessageConverter();
    }
}
