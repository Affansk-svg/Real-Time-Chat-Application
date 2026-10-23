package com.chat.chat;

import java.security.Principal;

/** The authenticated identity of a WebSocket session; getName() is the user id so convertAndSendToUser(id, ...) works. */
public record ChatPrincipal(Long userId, String username) implements Principal {
    @Override
    public String getName() { return String.valueOf(userId); }
}
