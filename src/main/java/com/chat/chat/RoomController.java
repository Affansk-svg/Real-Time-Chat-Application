package com.chat.chat;

import com.chat.chat.ChatDtos.*;
import com.chat.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {
    private final RoomService rooms;
    private final MessageService messages;

    @GetMapping
    public List<RoomDto> mine(@AuthenticationPrincipal AuthUser u) { return rooms.myRooms(u.id()); }

    @GetMapping("/{id}")
    public RoomDto get(@PathVariable Long id, @AuthenticationPrincipal AuthUser u) { return rooms.view(id, u.id()); }

    @PostMapping("/private/{userId}")
    public RoomDto privateRoom(@PathVariable Long userId, @AuthenticationPrincipal AuthUser u) { return rooms.getOrCreatePrivate(u.id(), userId); }

    @PostMapping("/group")
    public ResponseEntity<RoomDto> group(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody CreateGroupRequest r) {
        return ResponseEntity.status(201).body(rooms.createGroup(u.id(), r));
    }

    @PostMapping("/{id}/members")
    public RoomDto addMember(@PathVariable Long id, @AuthenticationPrincipal AuthUser u, @Valid @RequestBody AddMemberRequest r) {
        return rooms.addMember(id, u.id(), r.userId());
    }

    @DeleteMapping("/{id}/members/me")
    public ResponseEntity<Void> leave(@PathVariable Long id, @AuthenticationPrincipal AuthUser u) {
        rooms.leave(id, u.id());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> history(@PathVariable Long id, @AuthenticationPrincipal AuthUser u,
                                    @RequestParam(required = false) Long before, @RequestParam(defaultValue = "30") int size) {
        return messages.history(id, u.id(), before, size);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable Long id, @AuthenticationPrincipal AuthUser u) {
        rooms.markRead(id, u.id());
        return ResponseEntity.noContent().build();
    }
}
