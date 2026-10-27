package com.wy.camera.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wy.camera.status.client.LiveChannel;
import com.wy.camera.status.client.LiveGbsException;
import com.wy.camera.status.client.LiveGbsGateway;
import com.wy.camera.status.client.StreamStartResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 HTTP 链路测试：用 JDK 自带的 HttpServer 假装一台 LiveGBS，
 * 验证 Forest 调用的 URL 拼装、JSON 解码、异常翻译是否正确。
 *
 * <p>不需要 Mockito，也不需要启动真实平台。</p>
 *
 * @author baijin-chao
 */
@SpringBootTest(properties = {
        "camera-status.cron=-",
        "camera-status.thread-count=2",
        "camera-status.max-concurrency=2"
})
class ForestGatewayTests {

    private static final HttpServer SERVER;

    private static final int PORT;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/api/v1/login",
                    exchange -> respond(exchange, 200, "{\"URLToken\":\"test-token\"}"));
            SERVER.createContext("/api/v1/device/channellist",
                    exchange -> respond(exchange, 200,
                            "{\"ChannelList\":["
                                    + "{\"DeviceID\":\"d1\",\"ID\":\"c1\",\"Name\":\"cam-1\",\"Status\":\"ON\"},"
                                    + "{\"DeviceID\":\"d1\",\"ID\":\"c2\",\"Name\":\"cam-2\",\"Status\":\"OFF\"}]}"));
            SERVER.createContext("/api/v1/stream/start", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.contains("code=c2")) {
                    respond(exchange, 400, "device offline");
                } else {
                    respond(exchange, 200, "{\"StreamID\":\"stream-1\"}");
                }
            });
            SERVER.createContext("/api/v1/stream/stop",
                    exchange -> respond(exchange, 200, "{\"code\":0}"));
            SERVER.start();
            PORT = SERVER.getAddress().getPort();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void liveGbsProperties(DynamicPropertyRegistry registry) {
        registry.add("livegbs.base-url", () -> "http://127.0.0.1:" + PORT);
        registry.add("livegbs.username", () -> "admin");
        registry.add("livegbs.password", () -> "0123456789ABCDEF0123456789ABCDEF");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired
    private LiveGbsGateway gateway;

    @Test
    void shouldLoginAndMapChannelList() {
        String token = gateway.login();
        assertEquals("test-token", token);

        List<LiveChannel> channels = gateway.listChannels(token);
        assertEquals(2, channels.size());

        LiveChannel first = channels.get(0);
        assertEquals("d1", first.getDeviceId());
        assertEquals("c1", first.getChannelId());
        assertEquals("cam-1", first.getName());
        assertEquals("ON", first.getPlatformStatus());
        assertEquals("d1_c1", first.key());
    }

    @Test
    void shouldStartStreamSuccessfully() {
        StreamStartResult result = gateway.startStream("test-token",
                new LiveChannel("d1", "c1", "cam-1", "ON"));
        assertTrue(result.isStarted());
        assertEquals("stream-1", result.getStreamId());
    }

    @Test
    void shouldTranslateHttpErrorIntoDomainException() {
        LiveGbsException ex = assertThrows(LiveGbsException.class,
                () -> gateway.startStream("test-token", new LiveChannel("d1", "c2", "cam-2", "OFF")));

        assertEquals(Integer.valueOf(400), ex.getHttpStatus());
        assertFalse(ex.isAuthError(), "400 不应被当成 token 失效");
        assertFalse(ex.isPlatformError(), "400 是设备侧问题，不是平台故障");
    }

    @Test
    void stopStreamShouldNeverThrow() {
        gateway.stopStream("test-token", new LiveChannel("d1", "c1", "cam-1", "ON"));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
