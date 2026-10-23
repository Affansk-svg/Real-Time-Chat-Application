package com.chat.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PresenceServiceTest {
    private final PresenceService presence = new PresenceService();

    @Test
    void userIsOnlineUntilLastSessionCloses() {
        assertTrue(presence.connected(1L));      // first tab -> just came online
        assertFalse(presence.connected(1L));     // second tab -> already online
        assertFalse(presence.disconnected(1L));  // one tab still open
        assertTrue(presence.online().contains(1L));
        assertTrue(presence.disconnected(1L));   // last tab closed -> offline
        assertFalse(presence.online().contains(1L));
    }

    @Test
    void disconnectOfUnknownUserIsHarmless() {
        assertFalse(presence.disconnected(99L));
        assertTrue(presence.online().isEmpty());
    }
}
