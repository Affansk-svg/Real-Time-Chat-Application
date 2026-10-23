package com.chat.chat;

import com.chat.chat.ChatDtos.CreateGroupRequest;
import com.chat.common.ApiException;
import com.chat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {
    @Mock RoomRepository rooms;
    @Mock RoomMemberRepository members;
    @Mock MessageRepository messages;
    @Mock UserRepository users;
    @Mock ChatNotifier notifier;
    @InjectMocks RoomService service;

    @Test
    void privateKeyIsSymmetric() {
        assertEquals("3_7", RoomService.privateKey(7L, 3L));
        assertEquals(RoomService.privateKey(3L, 7L), RoomService.privateKey(7L, 3L));
    }

    @Test
    void cannotChatWithYourself() {
        assertEquals(400, assertThrows(ApiException.class, () -> service.getOrCreatePrivate(5L, 5L)).getStatus());
    }

    @Test
    void groupNeedsNameAndAtLeastTwoMembers() {
        assertEquals(400, assertThrows(ApiException.class, () -> service.createGroup(1L, new CreateGroupRequest("  ", List.of(2L)))).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> service.createGroup(1L, new CreateGroupRequest("Team", List.of()))).getStatus());
        verifyNoInteractions(rooms, notifier);
    }

    @Test
    void onlyGroupAdminsCanAddMembers() {
        Room group = new Room();
        group.setId(5L);
        group.setType(Room.Type.GROUP);
        RoomMember plain = new RoomMember();
        plain.setRole(RoomMember.Role.MEMBER);
        when(rooms.findById(5L)).thenReturn(Optional.of(group));
        when(members.findByRoomIdAndUserId(5L, 1L)).thenReturn(Optional.of(plain));

        ApiException e = assertThrows(ApiException.class, () -> service.addMember(5L, 1L, 9L));

        assertEquals(403, e.getStatus());
        verify(members, never()).save(any());
    }

    @Test
    void cannotAddMembersToPrivateChat() {
        Room priv = new Room();
        priv.setId(6L);
        priv.setType(Room.Type.PRIVATE);
        when(rooms.findById(6L)).thenReturn(Optional.of(priv));
        assertEquals(400, assertThrows(ApiException.class, () -> service.addMember(6L, 1L, 2L)).getStatus());
    }
}
