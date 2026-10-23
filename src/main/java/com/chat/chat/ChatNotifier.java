package com.chat.chat;

import com.chat.chat.ChatDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Single place that pushes real-time events to WebSocket subscribers. */
@Component
@RequiredArgsConstructor
public class ChatNotifier {
    private final SimpMessagingTemplate template;

    public void message(Long roomId, MessageDto m) { template.convertAndSend("/topic/rooms/" + roomId, m); }

    public void typing(Long roomId, Long userId, String username) {
        template.convertAndSend("/topic/rooms/" + roomId + "/typing", Map.of("userId", userId, "username", username));
    }

    public void roomAdded(Long userId, RoomDto room) {
        template.convertAndSendToUser(String.valueOf(userId), "/queue/events", new ChatEvent("ROOM_ADDED", room));
    }

    public void presence(Long userId, boolean online) { template.convertAndSend("/topic/presence", new PresenceEvent(userId, online)); }
}
