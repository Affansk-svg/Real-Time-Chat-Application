package com.chat.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Keyset pagination (id < before) is stable and fast with the (room_id, id) index. */
    @Query("select m from Message m join fetch m.sender where m.room.id = :roomId and m.id < :before order by m.id desc")
    List<Message> history(@Param("roomId") Long roomId, @Param("before") Long before, Pageable pageable);

    /** Latest message of every room in a single query. */
    @Query("select m from Message m join fetch m.sender where m.id in "
            + "(select max(m2.id) from Message m2 where m2.room.id in :roomIds group by m2.room.id)")
    List<Message> lastMessages(@Param("roomIds") Collection<Long> roomIds);

    /** Unread counts for many rooms in a single query: [roomId, count]. */
    @Query("select m.room.id, count(m) from Message m, RoomMember rm where rm.room.id = m.room.id "
            + "and rm.user.id = :userId and m.id > rm.lastReadMessageId and m.sender.id <> :userId "
            + "and m.room.id in :roomIds group by m.room.id")
    List<Object[]> unreadCounts(@Param("userId") Long userId, @Param("roomIds") Collection<Long> roomIds);

    @Query("select coalesce(max(m.id), 0) from Message m where m.room.id = :roomId")
    Long maxId(@Param("roomId") Long roomId);
}
