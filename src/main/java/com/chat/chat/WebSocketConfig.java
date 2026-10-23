package com.chat.chat;

import com.chat.security.AuthUser;
import com.chat.security.JwtUtil;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;

/**
 * STOMP over SockJS at /ws. Security rules enforced on every inbound frame:
 *  CONNECT   needs "Authorization: Bearer <jwt>"
 *  SUBSCRIBE only /topic/presence, own /user/queue/*, and /topic/rooms/{id}[/typing] of rooms the user belongs to
 *  SEND      only to /app/** (so a client can never publish straight onto a topic)
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtUtil jwt;
    private final RoomMemberRepository members;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (acc == null || acc.getCommand() == null) return message;
                StompCommand cmd = acc.getCommand();
                if (cmd == StompCommand.CONNECT) {
                    authenticate(acc);
                } else if (cmd == StompCommand.SUBSCRIBE) {
                    authorizeSubscribe(acc.getUser(), acc.getDestination());
                } else if (cmd == StompCommand.SEND) {
                    String d = acc.getDestination();
                    if (!(acc.getUser() instanceof ChatPrincipal) || d == null || !d.startsWith("/app/"))
                        throw new MessagingException("Send denied");
                }
                return message;
            }
        });
    }

    private void authenticate(StompHeaderAccessor acc) {
        String h = acc.getFirstNativeHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) throw new MessagingException("Missing token");
        try {
            AuthUser u = jwt.toUser(jwt.parse(h.substring(7)));
            acc.setUser(new ChatPrincipal(u.id(), u.username()));
        } catch (JwtException | IllegalArgumentException e) {
            throw new MessagingException("Invalid token");
        }
    }

    private void authorizeSubscribe(Principal principal, String dest) {
        if (!(principal instanceof ChatPrincipal p) || dest == null) throw new MessagingException("Subscription denied");
        if (dest.equals("/topic/presence") || dest.startsWith("/user/queue/")) return;
        if (dest.startsWith("/topic/rooms/")) {
            long roomId;
            try {
                roomId = Long.parseLong(dest.split("/")[3]);
            } catch (RuntimeException e) {
                throw new MessagingException("Bad destination");
            }
            if (members.existsByRoomIdAndUserId(roomId, p.userId())) return;
        }
        throw new MessagingException("Subscription denied");
    }
}
