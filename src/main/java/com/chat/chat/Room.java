package com.chat.chat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** A conversation: PRIVATE (exactly two members, unique privateKey) or GROUP. */
@Entity
@Table(name = "chat_rooms")
@Getter @Setter
public class Room {
    public enum Type { PRIVATE, GROUP }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false)
    private Type type;

    @Column(name = "room_name")
    private String name;

    @Column(unique = true)
    private String privateKey;

    private Long createdBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}
