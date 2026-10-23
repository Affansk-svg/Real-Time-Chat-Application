package com.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end over real HTTP against the full Spring context (H2 in MySQL mode). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthIntegrationTest {
    @Autowired TestRestTemplate rest;

    private String unique() { return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10); }

    private ResponseEntity<Map> register(String username, String password) {
        return rest.postForEntity("/api/auth/register", Map.of("username", username, "displayName", "Test", "password", password), Map.class);
    }

    @Test
    void registerLoginAndReadProfile() {
        String name = unique();
        ResponseEntity<Map> reg = register(name, "secret1");
        assertEquals(HttpStatus.CREATED, reg.getStatusCode());
        assertNotNull(reg.getBody().get("token"));

        ResponseEntity<Map> login = rest.postForEntity("/api/auth/login", Map.of("username", name, "password", "secret1"), Map.class);
        assertEquals(HttpStatus.OK, login.getStatusCode());

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth((String) login.getBody().get("token"));
        ResponseEntity<Map> me = rest.exchange("/api/users/me", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertEquals(HttpStatus.OK, me.getStatusCode());
        assertEquals(name, me.getBody().get("username"));
    }

    @Test
    void duplicateUsernameIsRejectedWith409() {
        String name = unique();
        assertEquals(HttpStatus.CREATED, register(name, "secret1").getStatusCode());
        assertEquals(HttpStatus.CONFLICT, register(name, "secret1").getStatusCode());
    }

    @Test
    void wrongPasswordReturns401() {
        String name = unique();
        register(name, "secret1");
        ResponseEntity<Map> r = rest.postForEntity("/api/auth/login", Map.of("username", name, "password", "wrong-pass"), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
    }

    @Test
    void invalidRegistrationReturns400() {
        assertEquals(HttpStatus.BAD_REQUEST, register("a b", "secret1").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, register(unique(), "123").getStatusCode());
    }

    @Test
    void protectedEndpointsRequireAToken() {
        assertEquals(HttpStatus.UNAUTHORIZED, rest.getForEntity("/api/rooms", String.class).getStatusCode());
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("not.a.jwt");
        assertEquals(HttpStatus.UNAUTHORIZED, rest.exchange("/api/rooms", HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode());
    }
}
