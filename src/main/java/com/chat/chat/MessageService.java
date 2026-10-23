package com.chat.chat;

import com.chat.chat.ChatDtos.MessageDto;
import com.chat.common.ApiException;
import com.chat.user.User;
import com.chat.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageService {
    public static final int MAX_LENGTH = 2000;

    private final MessageRepository messages;
    private final RoomMemberRepository members;
    private final RoomRepository rooms;
    private final UserRepository users;

    /** Validates, checks membership and persists. The caller broadcasts after this transaction commits. */
    @Transactional
    public MessageDto send(Long roomId, Long senderId, String content) {
        if (content == null || content.isBlank()) throw ApiException.badRequest("Message cannot be empty");
        String text = content.strip();
        if (text.length() > MAX_LENGTH) throw ApiException.badRequest("Message is too long (max " + MAX_LENGTH + " characters)");
        if (!members.existsByRoomIdAndUserId(roomId, senderId)) throw ApiException.forbidden("Not a member of this room");
        User sender = users.findById(senderId).orElseThrow(() -> ApiException.unauthorized("Unknown user"));
        Message m = new Message();
        m.setRoom(rooms.getReferenceById(roomId));
        m.setSender(sender);
        m.setContent(text);
        m = messages.save(m);
        return new MessageDto(m.getId(), roomId, sender.getId(), sender.getDisplayName(), m.getContent(), m.getCreatedAt());
    }

    /** Oldest-first page of messages older than {@code before}; pass null for the latest page. */
    @Transactional(readOnly = true)
    public List<MessageDto> history(Long roomId, Long userId, Long before, int size) {
        if (!members.existsByRoomIdAndUserId(roomId, userId)) throw ApiException.forbidden("Not a member of this room");
        long cursor = before == null ? Long.MAX_VALUE : before;
        List<MessageDto> page = new ArrayList<>(messages.history(roomId, cursor, PageRequest.of(0, Math.min(Math.max(size, 1), 100)))
                .stream().map(MessageDto::of).toList());
        Collections.reverse(page);
        return page;
    }
}
