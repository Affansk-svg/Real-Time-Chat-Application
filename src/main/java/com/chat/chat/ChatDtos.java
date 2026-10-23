package com.chat.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class ChatDtos {
    private ChatDtos() {}

    public record MessageDto(Long id, Long roomId, Long senderId, String senderName, String content, LocalDateTime createdAt) {
        public static MessageDto of(Message m) {
            return new MessageDto(m.getId(), m.getRoom().getId(), m.getSender().getId(), m.getSender().getDisplayName(),
                    m.getContent(), m.getCreatedAt());
        }
    }

    public record MemberDto(Long userId, String username, String displayName, String role) {}

    public record RoomDto(Long id, String type, String name, List<MemberDto> members, MessageDto lastMessage,
                          long unread, LocalDateTime createdAt) {}

    public record CreateGroupRequest(@NotBlank @Size(max = 60) String name, List<Long> memberIds) {}
    public record AddMemberRequest(@NotNull Long userId) {}
    public record SendMessage(String content) {}

    /** Pushed on /user/queue/events (e.g. ROOM_ADDED). */
    public record ChatEvent(String type, Object data) {}
    public record PresenceEvent(Long userId, boolean online) {}
}
