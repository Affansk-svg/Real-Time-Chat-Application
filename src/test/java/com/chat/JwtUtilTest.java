package com.chat;

import com.chat.security.AuthUser;
import com.chat.security.JwtUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    private final JwtUtil jwt = new JwtUtil("unit-test-secret-key-that-is-long-enough-123456", 60_000);

    @Test
    void tokenRoundTripKeepsIdentity() {
        AuthUser u = jwt.toUser(jwt.parse(jwt.generate(7L, "alice")));
        assertEquals(7L, u.id());
        assertEquals("alice", u.username());
    }

    @Test
    void tamperedTokenIsRejected() {
        String t = jwt.generate(1L, "bob");
        assertThrows(Exception.class, () -> jwt.parse(t.substring(0, t.length() - 3) + "abc"));
    }

    @Test
    void expiredTokenIsRejected() {
        JwtUtil shortLived = new JwtUtil("unit-test-secret-key-that-is-long-enough-123456", -1000);
        String t = shortLived.generate(1L, "bob");
        assertThrows(Exception.class, () -> shortLived.parse(t));
    }
}
