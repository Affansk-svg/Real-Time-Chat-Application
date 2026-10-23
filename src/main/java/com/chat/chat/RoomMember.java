package com.chat.chat;

import com.chat.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "room_members",
        uniqueConstraints = @UniqueConstraint(name = "uk_room_user", columnNames = {"room_id", "user_id"}),
        indexes = @Index(name = "idx_member_user", columnList = "user_id"))
@Getter @Setter
public class RoomMember {
    public enum Role { ADMIN, MEMBER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false)
    private Role role = Role.MEMBER;

    /** Highest message id this member has read; unread = newer messages from other people. */
    private long lastReadMessageId;

    private LocalDateTime joinedAt = LocalDateTime.now();
}
