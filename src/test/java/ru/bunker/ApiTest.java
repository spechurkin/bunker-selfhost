package ru.bunker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    @LocalServerPort
    int port;

    @Test
    void realHttpSupportsTemporaryRoomAndEnforcesBearerToken() throws Exception {
        var post = HttpRequest.newBuilder(uri("/api/rooms")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Игрок\",\"rulesetId\":\"classic\"}")).build();
        var created = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode());
        var ticket = mapper.readTree(created.body());
        var url = "/api/rooms/" + ticket.get("code").asText();
        assertEquals(401, client.send(HttpRequest.newBuilder(uri(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var view = client.send(HttpRequest.newBuilder(uri(url)).header("Authorization", "Bearer " + ticket.get("token").asText()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, view.statusCode());
        assertTrue(view.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals("LOBBY", mapper.readTree(view.body()).get("phase").asText());
        assertTrue(mapper.readTree(view.body()).get("pack").get("abilitiesEnabled").asBoolean());
    }

    @Test
    void rejectsUnknownFieldsAndOversizedBodies() throws Exception {
        var invalid = HttpRequest.newBuilder(uri("/api/rooms")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Test\",\"rulesetId\":\"classic\",\"host\":true}")).build();
        assertEquals(400, client.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode());
        var oversized = HttpRequest.newBuilder(uri("/api/rooms")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("x".repeat(2 * 1024 * 1024 + 1))).build();
        assertEquals(413, client.send(oversized, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void servesInterfaceAndHealth() throws Exception {
        assertEquals(200, client.send(HttpRequest.newBuilder(uri("/")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, client.send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void classicEndpointAndRoomStartUseTheCompletePdfPack() throws Exception {
        var classic = client.send(HttpRequest.newBuilder(uri("/api/rules/classic")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, classic.statusCode());
        var pack = mapper.readTree(classic.body());
        assertEquals(6, pack.get("categories").size());
        assertEquals(11, pack.get("threats").size());
        assertEquals(30, pack.get("abilities").get("cards").size());
        var created = client.send(HttpRequest.newBuilder(uri("/api/rooms")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"PDF host\",\"rulesetId\":\"classic\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode());
        var ticket = mapper.readTree(created.body());
        var roomUrl = "/api/rooms/" + ticket.get("code").asText();
        for (int i = 1; i <= 3; i++) {
            var joined = client.send(HttpRequest.newBuilder(uri(roomUrl + "/join")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"PDF player " + i + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, joined.statusCode());
        }
        var started = client.send(HttpRequest.newBuilder(uri(roomUrl + "/actions"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + ticket.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"start\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, started.statusCode());
        var room = mapper.readTree(started.body());
        assertEquals("EXPLORATION", room.get("phase").asText());
        assertTrue(room.get("catastrophe").get("id").asText().startsWith("classic-p"));
        assertEquals(5, room.get("bunker").size());
        assertEquals(6, room.get("players").get(0).get("sheet").size());
        assertTrue(!"manual".equals(room.get("players").get(0).get("ability").get("effect").asText()));
        assertTrue(room.get("players").get(0).has("abilities"));
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
