package com.grabfood.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Bài 1: Cấu hình Distributed Cache với Redis + Lettuce driver + JSON Serializer.
 *
 * Điểm khác biệt so với Jedis + Java Serializer:
 *  - Sử dụng LettuceConnectionFactory (non-blocking, thread-safe).
 *  - Dữ liệu lưu dạng JSON (GenericJackson2JsonRedisSerializer) → đọc được bằng redis-cli.
 *  - Không hard-code host/port/timeout; tất cả lấy từ application.yml.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    // ----------------------------------------------------------------
    // Inject từ application.yml – KHÔNG hard-code trong code Java
    // ----------------------------------------------------------------
    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    // ----------------------------------------------------------------
    // Bean 1: RedisConnectionFactory (dùng Lettuce – non-blocking)
    // ----------------------------------------------------------------

    /**
     * Khai báo kết nối tới Redis từ cấu hình trong application.yml.
     * Spring Boot tự động kéo Lettuce driver khi thêm dependency
     * spring-boot-starter-data-redis, nên KHÔNG cần thêm Jedis.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration serverConfig =
                new RedisStandaloneConfiguration(redisHost, redisPort);
        // LettuceConnectionFactory sử dụng pool cấu hình từ application.yml
        // (spring.data.redis.lettuce.pool.max-active = 10)
        return new LettuceConnectionFactory(serverConfig);
    }

    // ----------------------------------------------------------------
    // Bean 2: RedisCacheManager – dùng JSON Serializer
    // ----------------------------------------------------------------

    /**
     * Cấu hình CacheManager để dùng Redis làm Distributed Cache.
     *
     * Serializer: GenericJackson2JsonRedisSerializer
     *   → Lưu dữ liệu dạng JSON thay vì nhị phân (Java Serializer).
     *   → Kết quả có thể đọc được qua redis-cli:
     *        redis-cli> GET "restaurantMenu::101"
     *        [{"id":1,"name":"Phở Bò","price":55000}, ...]
     *
     * TTL mặc định: 10 phút (có thể tuỳ chỉnh per-cache).
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        // ObjectMapper hỗ trợ lưu thông tin kiểu (type info) để deserialize đúng class
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                // Key lưu dạng String thuần (dễ đọc trên redis-cli)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                // Value lưu dạng JSON
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                // TTL mặc định 10 phút
                .entryTtl(Duration.ofMinutes(10))
                // Không lưu cache khi value là null
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfig)
                .build();
    }
}
