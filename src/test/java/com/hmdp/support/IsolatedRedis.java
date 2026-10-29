package com.hmdp.support;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** 仅连接本工具启动的临时 Redis，不使用开发环境连接配置。 */
public class IsolatedRedis implements AutoCloseable {
    private Process process;
    private LettuceConnectionFactory connection;
    private StringRedisTemplate template;

    public IsolatedRedis() throws Exception {
        Path target = Paths.get("target").toAbsolutePath();
        Files.createDirectories(target);
        Path directory = Files.createTempDirectory(target, "redis-features-");
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        try {
            process = new ProcessBuilder(executable(), "--bind", "127.0.0.1", "--port", String.valueOf(port),
                    "--save", "", "--appendonly", "no", "--dir", directory.toString())
                    .redirectErrorStream(true).redirectOutput(directory.resolve("redis.log").toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (true) {
                if (!process.isAlive() || System.nanoTime() > deadline) {
                    throw new IllegalStateException("临时 Redis 启动失败：" + directory);
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                    break;
                } catch (IOException notReady) {
                    Thread.sleep(50);
                }
            }
            connection = new LettuceConnectionFactory("127.0.0.1", port);
            connection.afterPropertiesSet();
            template = new StringRedisTemplate(connection);
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    private String executable() {
        String configured = System.getProperty("hmdp.test.redis.executable", System.getenv("REDIS_SERVER"));
        if (configured != null && !configured.trim().isEmpty()) {
            return configured;
        }
        Path windows = Paths.get("D:/Redis/Redis-x64-3.2.100/redis-server.exe");
        return Files.isRegularFile(windows) ? windows.toString() : "redis-server";
    }

    public StringRedisTemplate template() {
        return template;
    }

    public void clear() {
        try (RedisConnection client = connection.getConnection()) {
            client.flushDb();
        }
    }

    @Override
    public void close() throws Exception {
        try {
            if (connection != null) {
                connection.destroy();
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }
    }
}
