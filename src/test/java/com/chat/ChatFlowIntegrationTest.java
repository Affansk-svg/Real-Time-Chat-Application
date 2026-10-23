package com.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Full end-to-end: real HTTP + real WebSocket/STOMP clients against the running app and the database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChatFlowIntegrationTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private WebSocketStompClient stomp;
    private final List<StompSession> sessions = new ArrayList<>();

    record Account(long id, String token) {}

    @BeforeEach
    void setUp() {
        stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
        stomp.stop();
    }

    // ---------- helpers ----------
    private Account register() {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Map<?, ?> body = rest.postForEntity("/api/auth/register",
                Map.of("username", name, "displayName", name, "password", "secret1"), Map.class).getBody();
        return new Account(((Number) body.get("userId")).longValue(), (String) body.get("token"));
    }

    private <T> ResponseEntity<T> call(HttpMethod m, String path, Object body, Account as, Class<T> type) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(as.token());
        return rest.exchange(path, m, new HttpEntity<>(body, h), type);
    }

    private long privateRoom(Account me, Account other) {
        return ((Number) call(HttpMethod.POST, "/api/rooms/private/" + other.id(), null, me, Map.class).getBody().get("id")).longValue();
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders h = new StompHeaders();
        if (token != null) h.add("Authorization", "Bearer " + token);
        StompSession s = stomp.connectAsync("ws://localhost:" + port + "/ws/websocket", new WebSocketHttpHeaders(), h,
                new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
        sessions.add(s);
        return s;
    }

    @SuppressWarnings("unchecked")
    private BlockingQueue<Map<String, Object>> subscribe(StompSession s, String destination) throws Exception {
        BlockingQueue<Map<String, Object>> q = new LinkedBlockingQueue<>();
        s.subscribe(destination, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { q.add((Map<String, Object>) payload); }
        });
        Thread.sleep(400); // let the SUBSCRIBE frame reach the broker
        return q;
    }

    // ---------- tests ----------
    @Test
    void privateMessageIsDeliveredInRealTimeAndPersisted() throws Exception {
        Account alice = register(), bob = register();
        long roomId = privateRoom(alice, bob);
        assertEquals(roomId, privateRoom(bob, alice), "the same pair must always share one private room");

        StompSession aliceWs = connect(alice.token());
        StompSession bobWs = connect(bob.token());
        BlockingQueue<Map<String, Object>> bobInbox = subscribe(bobWs, "/topic/rooms/" + roomId);

        aliceWs.send("/app/rooms/" + roomId + "/send", Map.of("content", "Hello Bob"));

        Map<String, Object> received = bobInbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(received, "bob should receive the message in real time");
        assertEquals("Hello Bob", received.get("content"));
        assertEquals(alice.id(), ((Number) received.get("senderId")).longValue());

        Map<?, ?>[] history = call(HttpMethod.GET, "/api/rooms/" + roomId + "/messages", null, bob, Map[].class).getBody();
        assertEquals(1, history.length);
        assertEquals("Hello Bob", history[0].get("content"));

        Map<?, ?>[] rooms = call(HttpMethod.GET, "/api/rooms", null, bob, Map[].class).getBody();
        assertEquals(1, ((Number) rooms[0].get("unread")).intValue(), "bob has one unread message");
        call(HttpMethod.POST, "/api/rooms/" + roomId + "/read", null, bob, Void.class);
        rooms = call(HttpMethod.GET, "/api/rooms", null, bob, Map[].class).getBody();
        assertEquals(0, ((Number) rooms[0].get("unread")).intValue());
    }

    @Test
    void groupMessageReachesEveryMember() throws Exception {
        Account alice = register(), bob = register(), carol = register();
        Map<?, ?> group = call(HttpMethod.POST, "/api/rooms/group",
                Map.of("name", "Project X", "memberIds", List.of(bob.id(), carol.id())), alice, Map.class).getBody();
        long roomId = ((Number) group.get("id")).longValue();

        BlockingQueue<Map<String, Object>> aliceQ = subscribe(connect(alice.token()), "/topic/rooms/" + roomId);
        StompSession bobWs = connect(bob.token());
        BlockingQueue<Map<String, Object>> carolQ = subscribe(connect(carol.token()), "/topic/rooms/" + roomId);

        bobWs.send("/app/rooms/" + roomId + "/send", Map.of("content", "standup at 10"));

        assertEquals("standup at 10", aliceQ.poll(5, TimeUnit.SECONDS).get("content"));
        assertEquals("standup at 10", carolQ.poll(5, TimeUnit.SECONDS).get("content"));
    }

    @Test
    void outsiderCannotReadOrListenToARoom() throws Exception {
        Account alice = register(), bob = register(), eve = register();
        long roomId = privateRoom(alice, bob);

        assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.GET, "/api/rooms/" + roomId + "/messages", null, eve, String.class).getStatusCode());

        StompSession eveWs = connect(eve.token());
        BlockingQueue<Map<String, Object>> eveQ = subscribe(eveWs, "/topic/rooms/" + roomId);
        StompSession aliceWs = connect(alice.token());
        aliceWs.send("/app/rooms/" + roomId + "/send", Map.of("content", "secret"));
        assertNull(eveQ.poll(1500, TimeUnit.MILLISECONDS), "eve must never receive messages of a room she is not in");
    }

    @Test
    void nonMemberCannotSendAndGetsAnErrorBack() throws Exception {
        Account alice = register(), bob = register(), eve = register();
        long roomId = privateRoom(alice, bob);
        StompSession eveWs = connect(eve.token());
        BlockingQueue<Map<String, Object>> errors = subscribe(eveWs, "/user/queue/errors");

        eveWs.send("/app/rooms/" + roomId + "/send", Map.of("content", "let me in"));

        Map<String, Object> err = errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(err);
        assertEquals("Not a member of this room", err.get("error"));
        Map<?, ?>[] history = call(HttpMethod.GET, "/api/rooms/" + roomId + "/messages", null, alice, Map[].class).getBody();
        assertEquals(0, history.length);
    }

    @Test
    void oversizedMessageIsRejectedAndNotStored() throws Exception {
        Account alice = register(), bob = register();
        long roomId = privateRoom(alice, bob);
        StompSession aliceWs = connect(alice.token());
        BlockingQueue<Map<String, Object>> errors = subscribe(aliceWs, "/user/queue/errors");

        aliceWs.send("/app/rooms/" + roomId + "/send", Map.of("content", "x".repeat(2001)));

        assertTrue(String.valueOf(errors.poll(5, TimeUnit.SECONDS).get("error")).contains("too long"));
        assertEquals(0, call(HttpMethod.GET, "/api/rooms/" + roomId + "/messages", null, alice, Map[].class).getBody().length);
    }

    @Test
    void websocketConnectionWithoutValidTokenIsRejected() {
        assertThrows(Exception.class, () -> connect(null));
        assertThrows(Exception.class, () -> connect("garbage.token.value"));
    }
}
