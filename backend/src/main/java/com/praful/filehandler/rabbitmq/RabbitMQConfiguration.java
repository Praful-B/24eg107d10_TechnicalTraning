package com.praful.filehandler.rabbitmq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SmartMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queue topology. The backend owns it: the Python worker only consumes, so the
 * arguments below can never drift out of sync with the worker's view of the world.
 *
 * <ul>
 *     <li>chunk work items land on {@code preprocessing_queue};</li>
 *     <li>failed items are retried through {@code preprocessing_retry_queue}
 *         (TTL delay, then dead-lettered back to the main queue);</li>
 *     <li>items that exhaust their retries end up on {@code dead_letter_queue}.</li>
 * </ul>
 */
@Configuration
public class RabbitMQConfiguration {
    public static final String PREPROCESSING_QUEUE_NAME = "preprocessing_queue";
    public static final String POSTPROCESSING_QUEUE_NAME = "postprocessing_queue";
    public static final String EXCHANGE_NAME = "processing_exchange";
    public static final String PREPROCESSING_ROUTING_KEY = "preprocessing_routing_key";
    public static final String POSTPROCESSING_ROUTING_KEY = "postprocessing_routing_key";

    public static final String DEAD_LETTER_EXCHANGE = "processing_dlx";
    public static final String DEAD_LETTER_QUEUE = "dead_letter_queue";
    public static final String RETRY_QUEUE_NAME = "preprocessing_retry_queue";
    public static final String RETRY_ROUTING_KEY = "preprocessing_retry_routing_key";
    /**
     * How long a chunk waits before RabbitMQ hands it back to the worker.
     * The retry <em>limit</em> lives in the worker (CHUNK_MAX_ATTEMPTS), which is the
     * side that decides whether an attempt failed.
     */
    public static final int RETRY_DELAY_MS = 15_000;

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfiguration.class);

    @Bean
    Queue preprocessingQueue() {
        return QueueBuilder.durable(PREPROCESSING_QUEUE_NAME)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    Queue postprocessingQueue() {
        return QueueBuilder.durable(POSTPROCESSING_QUEUE_NAME)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .build();
    }

    /** Delay queue: holds a failed chunk for {@link #RETRY_DELAY_MS}, then re-publishes it. */
    @Bean
    Queue preprocessingRetryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE_NAME)
                .ttl(RETRY_DELAY_MS)
                .deadLetterExchange(EXCHANGE_NAME)
                .deadLetterRoutingKey(PREPROCESSING_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    TopicExchange exchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    @Bean
    TopicExchange deadLetterExchange() {
        return new TopicExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    Binding preprocessingBinding(Queue preprocessingQueue, TopicExchange exchange) {
        return BindingBuilder.bind(preprocessingQueue).to(exchange).with(PREPROCESSING_ROUTING_KEY);
    }

    @Bean
    Binding postprocessingBinding(Queue postprocessingQueue, TopicExchange exchange) {
        return BindingBuilder.bind(postprocessingQueue).to(exchange).with(POSTPROCESSING_ROUTING_KEY);
    }

    @Bean
    Binding retryBinding(Queue preprocessingRetryQueue, TopicExchange exchange) {
        return BindingBuilder.bind(preprocessingRetryQueue).to(exchange).with(RETRY_ROUTING_KEY);
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("#");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        // Jackson 3 based converter (Spring AMQP 4). Unknown fields are ignored
        // on the result DTO via @JsonIgnoreProperties.
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        // Surface a chunk message that the broker never accepted instead of losing it silently.
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returned ->
                log.error("Chunk message was not routed ({}): {}", returned.getReplyText(),
                        new String(returned.getMessage().getBody())));
        rabbitTemplate.setConfirmCallback((correlation, ack, cause) -> {
            if (!ack) {
                log.error("Broker nacked a chunk message: {}", cause);
            }
        });
        return rabbitTemplate;
    }

    /**
     * The result consumer only exists in queue mode. In synchronous mode we never
     * publish chunk work, so starting the listener would just spam connection
     * attempts against the broker.
     */
    @Bean
    @ConditionalOnProperty(name = "app.transcription.mode", havingValue = "queue", matchIfMissing = true)
    SimpleMessageListenerContainer simpleMessageListenerContainer(ConnectionFactory connectionFactory,
                                                                  MessageProcesser messageProcesser,
                                                                  MessageConverter messageConverter) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setQueueNames(POSTPROCESSING_QUEUE_NAME);
        // The worker publishes plain JSON with no __TypeId__ header, so the converter has to be
        // told the target type; otherwise it falls back to a LinkedHashMap and the invocation fails.
        container.setMessageListener((MessageListener) message -> {
            Object payload = messageConverter instanceof SmartMessageConverter smart
                    ? smart.fromMessage(message, new ParameterizedTypeReference<TranscriptionResultDto>() { })
                    : messageConverter.fromMessage(message);
            messageProcesser.processMessage((TranscriptionResultDto) payload);
        });
        // One result at a time keeps the "all chunks received?" check cheap and predictable.
        container.setPrefetchCount(1);
        // A failing result is dead-lettered rather than requeued forever.
        container.setDefaultRequeueRejected(false);
        return container;
    }
}
