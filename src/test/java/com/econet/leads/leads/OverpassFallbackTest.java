package com.econet.leads.leads;

import com.econet.leads.exception.ApiException;
import com.econet.leads.integration.osm.OverpassClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Endpoint fallback of OverpassClient against local fake servers. */
class OverpassFallbackTest {

    static final String OK = "{\"elements\":[{\"type\":\"node\",\"id\":1}]}";

    final List<HttpServer> servers = new ArrayList<>();
    final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void reset() {
        hits.clear();
    }

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    String fake(String name, int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/interpreter", ex -> {
            hits.add(name);
            ex.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/interpreter";
    }

    OverpassClient client(String primary, String... mirrors) {
        return new OverpassClient(primary, String.join(",", mirrors), 60, "http://127.0.0.1:9", 3, 0, new ObjectMapper());
    }

    @Test
    void fallsThroughOn5xxAnd429ThenRemembersTheWorkingEndpoint() throws Exception {
        String a = fake("a", 504, "gateway timeout");
        String b = fake("b", 429, "slow down");
        String c = fake("c", 200, OK);
        OverpassClient client = client("", a, b, c);

        assertThat(client.run("[out:json];")).hasSize(1);
        assertThat(hits).containsExactly("a", "b", "c");

        hits.clear();
        assertThat(client.endpointOrder().get(0)).isEqualTo(c);
        assertThat(client.run("[out:json];")).hasSize(1);
        assertThat(hits).containsExactly("c");
    }

    @Test
    void connectErrorFallsThroughAndConfiguredUrlIsTriedFirstWithoutDuplicates() throws Exception {
        String good = fake("good", 200, OK);
        String dead = "http://127.0.0.1:9/api/interpreter";
        OverpassClient client = client(dead, good, dead);
        assertThat(client.endpointOrder()).containsExactly(dead, good);
        assertThat(client.run("[out:json];")).hasSize(1);
        assertThat(hits).containsExactly("good");
    }

    @Test
    void unusableAnswerFallsThrough() throws Exception {
        String junk = fake("junk", 200, "<html>maintenance</html>");
        String good = fake("good", 200, OK);
        assertThat(client("", junk, good).run("q")).hasSize(1);
        assertThat(hits).containsExactly("junk", "good");
    }

    @Test
    void allFailingIsA502() throws Exception {
        String a = fake("a", 500, "x");
        String b = fake("b", 503, "x");
        assertThatThrownBy(() -> client("", a, b).run("q"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(502));
        assertThat(hits).containsExactly("a", "b");
    }

    @Test
    void clientErrorFromTheQueryIsNotRetried() throws Exception {
        String a = fake("a", 400, "bad query");
        String b = fake("b", 200, OK);
        assertThatThrownBy(() -> client("", a, b).run("q")).isInstanceOf(ApiException.class);
        assertThat(hits).containsExactly("a");
    }

    @Test
    void blockedOrMovedMirrorFallsThroughToTheNextOne() throws Exception {
        String forbidden = fake("forbidden", 403, "blocked");
        String missing = fake("missing", 404, "not here");
        String good = fake("good", 200, OK);
        OverpassClient client = client(missing, forbidden, good);
        assertThat(client.run("q")).hasSize(1);
        assertThat(hits).containsExactly("missing", "forbidden", "good");
        assertThat(client.endpointOrder().get(0)).isEqualTo(good);
    }

    @Test
    void runtimeErrorRemarkIsNotASuccess() throws Exception {
        String loaded = fake("loaded", 200, "{\"remark\":\"runtime error: Query timed out in \\\"query\\\" at line 3\",\"elements\":[]}");
        String good = fake("good", 200, OK);
        OverpassClient client = client("", loaded, good);
        assertThat(client.run("q")).hasSize(1);
        assertThat(hits).containsExactly("loaded", "good");
        assertThat(client.endpointOrder().get(0)).isEqualTo(good);
    }

    @Test
    void noEndpointConfiguredIsA502() {
        assertThatThrownBy(() -> client("").run("q")).isInstanceOf(ApiException.class);
    }
}
