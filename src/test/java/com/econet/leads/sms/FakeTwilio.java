package com.econet.leads.sms;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal stand-in for the Twilio Messages endpoint: records requests, can be told to fail. */
final class FakeTwilio {

    record Call(String path, String authorization, Map<String, String> form) {
    }

    final HttpServer server;
    final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    volatile int nextStatus = 201;
    private final AtomicInteger counter = new AtomicInteger();

    FakeTwilio() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = new LinkedHashMap<>();
            for (String pair : raw.split("&")) {
                if (pair.isEmpty()) continue;
                String[] kv = pair.split("=", 2);
                form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
            calls.add(new Call(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"), form));
            int status = nextStatus;
            String json = status / 100 == 2 ? "{\"sid\":\"SM" + counter.incrementAndGet() + "\",\"status\":\"queued\"}"
                    : "{\"code\":21211,\"message\":\"Invalid 'To' Phone Number\"}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        calls.clear();
        nextStatus = 201;
    }
}
