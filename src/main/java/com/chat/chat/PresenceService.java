package com.chat.chat;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks online users; a user with several tabs/devices is online until the last session closes. */
@Service
public class PresenceService {
    private final ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();

    /** @return true if this was the user's first session (user just came online) */
    public boolean connected(Long userId) {
        AtomicBoolean first = new AtomicBoolean();
        sessions.merge(userId, 1, (old, one) -> old + one);
        sessions.computeIfPresent(userId, (k, v) -> { first.set(v == 1); return v; });
        return first.get();
    }

    /** @return true if that was the user's last session (user just went offline) */
    public boolean disconnected(Long userId) {
        AtomicBoolean last = new AtomicBoolean();
        sessions.computeIfPresent(userId, (k, v) -> {
            if (v <= 1) { last.set(true); return null; }
            return v - 1;
        });
        return last.get();
    }

    public Set<Long> online() { return Set.copyOf(sessions.keySet()); }
}
