package com.chat.chat;

import com.chat.chat.ChatDtos.MessageDto;
import com.chat.common.ApiException;
import com.chat.user.User;
import com.chat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {
    @Mock MessageRepository messages;
    @Mock RoomMemberRepository members;
    @Mock RoomRepository rooms;
    @Mock UserRepository users;
    @InjectMocks MessageService service;

    private User user(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setUsername(name);
        u.setDisplayName(name);
        return u;
    }

    private Message message(long id, Room room, User sender, String text) {
        Message m = new Message();
        m.setId(id);
        m.setRoom(room);
        m.setSender(sender);
        m.setContent(text);
        return m;
    }

    @Test
    void sendPersistsTrimmedMessageForMember() {
        Room room = new Room();
        room.setId(1L);
        when(members.existsByRoomIdAndUserId(1L, 2L)).thenReturn(true);
        when(users.findById(2L)).thenReturn(Optional.of(user(2L, "Bob")));
        when(rooms.getReferenceById(1L)).thenReturn(room);
        when(messages.save(any(Message.class))).thenAnswer(i -> {
            Message m = i.getArgument(0);
            m.setId(99L);
            return m;
        });

        MessageDto dto = service.send(1L, 2L, "  hello  ");

        assertEquals(99L, dto.id());
        assertEquals("hello", dto.content());
        assertEquals("Bob", dto.senderName());
        assertEquals(1L, dto.roomId());
    }

    @Test
    void sendByNonMemberIsForbiddenAndNothingIsSaved() {
        when(members.existsByRoomIdAndUserId(1L, 3L)).thenReturn(false);
        ApiException e = assertThrows(ApiException.class, () -> service.send(1L, 3L, "hi"));
        assertEquals(403, e.getStatus());
        verify(messages, never()).save(any());
    }

    @Test
    void blankAndOversizedMessagesAreRejected() {
        assertEquals(400, assertThrows(ApiException.class, () -> service.send(1L, 2L, "   ")).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> service.send(1L, 2L, null)).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> service.send(1L, 2L, "x".repeat(2001))).getStatus());
        verifyNoInteractions(messages);
    }

    @Test
    void historyIsReturnedOldestFirst() {
        Room room = new Room();
        room.setId(1L);
        User u = user(2L, "Bob");
        when(members.existsByRoomIdAndUserId(1L, 2L)).thenReturn(true);
        when(messages.history(1L, Long.MAX_VALUE, PageRequest.of(0, 30)))
                .thenReturn(List.of(message(12L, room, u, "second"), message(11L, room, u, "first")));

        List<MessageDto> page = service.history(1L, 2L, null, 30);

        assertEquals(List.of("first", "second"), page.stream().map(MessageDto::content).toList());
    }
}
