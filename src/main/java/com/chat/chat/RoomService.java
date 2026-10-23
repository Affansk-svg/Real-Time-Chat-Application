package com.chat.chat;

import com.chat.chat.ChatDtos.*;
import com.chat.common.ApiException;
import com.chat.user.User;
import com.chat.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class RoomService {
    private static final int MAX_GROUP_SIZE = 100;

    private final RoomRepository rooms;
    private final RoomMemberRepository members;
    private final MessageRepository messages;
    private final UserRepository users;
    private final ChatNotifier notifier;

    /** Same pair of users always maps to the same key, so a private chat can never be duplicated. */
    public static String privateKey(Long a, Long b) { return Math.min(a, b) + "_" + Math.max(a, b); }

    public RoomDto getOrCreatePrivate(Long me, Long otherId) {
        if (me.equals(otherId)) throw ApiException.badRequest("You cannot start a chat with yourself");
        User other = users.findById(otherId).orElseThrow(() -> ApiException.notFound("User not found"));
        User self = users.findById(me).orElseThrow(() -> ApiException.notFound("User not found"));
        Optional<Room> existing = rooms.findByPrivateKey(privateKey(me, otherId));
        if (existing.isPresent()) return view(existing.get().getId(), me);

        Room r = new Room();
        r.setType(Room.Type.PRIVATE);
        r.setPrivateKey(privateKey(me, otherId));
        r.setCreatedBy(me);
        r = rooms.save(r);
        addRow(r, self, RoomMember.Role.MEMBER);
        addRow(r, other, RoomMember.Role.MEMBER);
        notifier.roomAdded(otherId, view(r.getId(), otherId));
        return view(r.getId(), me);
    }

    public RoomDto createGroup(Long me, CreateGroupRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) throw ApiException.badRequest("Group name is required");
        if (name.length() > 60) throw ApiException.badRequest("Group name is too long");
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(me);
        if (req.memberIds() != null) ids.addAll(req.memberIds());
        if (ids.size() < 2) throw ApiException.badRequest("A group needs at least one other member");
        if (ids.size() > MAX_GROUP_SIZE) throw ApiException.badRequest("A group can have at most " + MAX_GROUP_SIZE + " members");
        List<User> found = users.findAllById(ids);
        if (found.size() != ids.size()) throw ApiException.badRequest("Unknown user in memberIds");

        Room r = new Room();
        r.setType(Room.Type.GROUP);
        r.setName(name);
        r.setCreatedBy(me);
        r = rooms.save(r);
        for (User u : found) addRow(r, u, u.getId().equals(me) ? RoomMember.Role.ADMIN : RoomMember.Role.MEMBER);
        for (User u : found) if (!u.getId().equals(me)) notifier.roomAdded(u.getId(), view(r.getId(), u.getId()));
        return view(r.getId(), me);
    }

    public RoomDto addMember(Long roomId, Long me, Long userId) {
        Room r = rooms.findById(roomId).orElseThrow(() -> ApiException.notFound("Room not found"));
        if (r.getType() != Room.Type.GROUP) throw ApiException.badRequest("Cannot add members to a private chat");
        RoomMember mine = members.findByRoomIdAndUserId(roomId, me).orElseThrow(() -> ApiException.forbidden("Not a member of this room"));
        if (mine.getRole() != RoomMember.Role.ADMIN) throw ApiException.forbidden("Only group admins can add members");
        if (members.existsByRoomIdAndUserId(roomId, userId)) throw ApiException.conflict("User is already a member");
        if (members.findByRoomIdFetchUser(roomId).size() >= MAX_GROUP_SIZE) throw ApiException.badRequest("Group is full");
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
        addRow(r, u, RoomMember.Role.MEMBER);
        notifier.roomAdded(userId, view(roomId, userId));
        return view(roomId, me);
    }

    public void leave(Long roomId, Long me) {
        Room r = rooms.findById(roomId).orElseThrow(() -> ApiException.notFound("Room not found"));
        if (r.getType() != Room.Type.GROUP) throw ApiException.badRequest("You cannot leave a private chat");
        RoomMember mine = members.findByRoomIdAndUserId(roomId, me).orElseThrow(() -> ApiException.forbidden("Not a member of this room"));
        members.delete(mine);
        members.flush();
        List<RoomMember> rest = members.findByRoomIdFetchUser(roomId);
        if (!rest.isEmpty() && rest.stream().noneMatch(m -> m.getRole() == RoomMember.Role.ADMIN)) rest.get(0).setRole(RoomMember.Role.ADMIN);
    }

    @Transactional(readOnly = true)
    public void requireMember(Long roomId, Long userId) {
        if (!members.existsByRoomIdAndUserId(roomId, userId)) throw ApiException.forbidden("Not a member of this room");
    }

    public void markRead(Long roomId, Long me) {
        RoomMember m = members.findByRoomIdAndUserId(roomId, me).orElseThrow(() -> ApiException.forbidden("Not a member of this room"));
        m.setLastReadMessageId(messages.maxId(roomId));
    }

    @Transactional(readOnly = true)
    public RoomDto view(Long roomId, Long me) {
        Room r = rooms.findById(roomId).orElseThrow(() -> ApiException.notFound("Room not found"));
        List<RoomMember> ms = members.findByRoomIdFetchUser(roomId);
        if (ms.stream().noneMatch(m -> m.getUser().getId().equals(me))) throw ApiException.forbidden("Not a member of this room");
        MessageDto last = messages.lastMessages(List.of(roomId)).stream().findFirst().map(MessageDto::of).orElse(null);
        long unread = unread(me, List.of(roomId)).getOrDefault(roomId, 0L);
        return toDto(r, ms, last, unread, me);
    }

    /** All rooms of a user with last message and unread count: 4 queries total, independent of the number of rooms. */
    @Transactional(readOnly = true)
    public List<RoomDto> myRooms(Long me) {
        List<RoomMember> mine = members.findByUserIdFetchRoom(me);
        if (mine.isEmpty()) return List.of();
        List<Long> ids = mine.stream().map(m -> m.getRoom().getId()).toList();
        Map<Long, List<RoomMember>> byRoom = members.findByRoomIds(ids).stream().collect(Collectors.groupingBy(m -> m.getRoom().getId()));
        Map<Long, MessageDto> last = messages.lastMessages(ids).stream().map(MessageDto::of).collect(Collectors.toMap(MessageDto::roomId, m -> m));
        Map<Long, Long> unread = unread(me, ids);
        return mine.stream()
                .map(m -> toDto(m.getRoom(), byRoom.getOrDefault(m.getRoom().getId(), List.of()), last.get(m.getRoom().getId()),
                        unread.getOrDefault(m.getRoom().getId(), 0L), me))
                .sorted(Comparator.comparing((RoomDto d) -> d.lastMessage() != null ? d.lastMessage().createdAt() : d.createdAt()).reversed())
                .toList();
    }

    private Map<Long, Long> unread(Long me, Collection<Long> roomIds) {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] row : messages.unreadCounts(me, roomIds)) out.put((Long) row[0], ((Number) row[1]).longValue());
        return out;
    }

    private RoomDto toDto(Room r, List<RoomMember> ms, MessageDto last, long unread, Long me) {
        List<MemberDto> md = ms.stream().map(m -> new MemberDto(m.getUser().getId(), m.getUser().getUsername(),
                m.getUser().getDisplayName(), m.getRole().name())).toList();
        String name = r.getName();
        if (r.getType() == Room.Type.PRIVATE) {
            name = md.stream().filter(x -> !x.userId().equals(me)).map(MemberDto::displayName).findFirst().orElse("Chat");
        }
        LocalDateTime created = r.getCreatedAt() == null ? LocalDateTime.now() : r.getCreatedAt();
        return new RoomDto(r.getId(), r.getType().name(), name, md, last, unread, created);
    }

    private void addRow(Room r, User u, RoomMember.Role role) {
        RoomMember m = new RoomMember();
        m.setRoom(r);
        m.setUser(u);
        m.setRole(role);
        members.save(m);
    }
}
