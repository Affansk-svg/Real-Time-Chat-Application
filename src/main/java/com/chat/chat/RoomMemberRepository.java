package com.chat.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {
    boolean existsByRoomIdAndUserId(Long roomId, Long userId);
    Optional<RoomMember> findByRoomIdAndUserId(Long roomId, Long userId);

    @Query("select rm from RoomMember rm join fetch rm.room where rm.user.id = :userId")
    List<RoomMember> findByUserIdFetchRoom(@Param("userId") Long userId);

    @Query("select rm from RoomMember rm join fetch rm.user join fetch rm.room where rm.room.id in :roomIds order by rm.id")
    List<RoomMember> findByRoomIds(@Param("roomIds") Collection<Long> roomIds);

    @Query("select rm from RoomMember rm join fetch rm.user where rm.room.id = :roomId order by rm.id")
    List<RoomMember> findByRoomIdFetchUser(@Param("roomId") Long roomId);
}
