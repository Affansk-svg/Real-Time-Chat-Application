package com.chat.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@RequiredArgsConstructor
public class PresenceListener {
    private final PresenceService presence;
    private final ChatNotifier notifier;

    @EventListener
    public void onConnect(SessionConnectedEvent e) {
        if (e.getUser() instanceof ChatPrincipal p && presence.connected(p.userId())) notifier.presence(p.userId(), true);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent e) {
        if (e.getUser() instanceof ChatPrincipal p && presence.disconnected(p.userId())) notifier.presence(p.userId(), false);
    }
}
