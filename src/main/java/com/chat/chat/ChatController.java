package com.chat.chat;

import com.chat.chat.ChatDtos.*;
import com.chat.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

/**
 * STOMP endpoints. Clients SEND to /app/rooms/{id}/send; the message is persisted first and then
 * broadcast to /topic/rooms/{id}, so what members see is exactly what is stored in MySQL.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatController {
    private final MessageService messages;
    private final RoomService rooms;
    private final ChatNotifier notifier;

    @MessageMapping("/rooms/{roomId}/send")
    public void send(@DestinationVariable Long roomId, @Payload SendMessage payload, Principal principal) {
        ChatPrincipal p = (ChatPrincipal) principal;
        MessageDto saved = messages.send(roomId, p.userId(), payload == null ? null : payload.content());
        notifier.message(roomId, saved);
    }

    @MessageMapping("/rooms/{roomId}/typing")
    public void typing(@DestinationVariable Long roomId, Principal principal) {
        ChatPrincipal p = (ChatPrincipal) principal;
        rooms.requireMember(roomId, p.userId());
        notifier.typing(roomId, p.userId(), p.username());
    }

    /** Errors go only to the sender, on /user/queue/errors. */
    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public Map<String, String> onError(Exception e) {
        if (!(e instanceof ApiException)) log.error("WebSocket handler error", e);
        return Map.of("error", e instanceof ApiException ? e.getMessage() : "Unexpected error");
    }
}
