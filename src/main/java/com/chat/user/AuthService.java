package com.chat.user;

import com.chat.common.ApiException;
import com.chat.security.JwtUtil;
import com.chat.user.UserDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtUtil jwt;

    @Transactional
    public AuthResponse register(RegisterRequest r) {
        String username = r.username().trim().toLowerCase();
        if (users.existsByUsername(username)) throw ApiException.conflict("Username already taken");
        User u = new User();
        u.setUsername(username);
        u.setDisplayName(r.displayName() == null || r.displayName().isBlank() ? username : r.displayName().trim());
        u.setPasswordHash(encoder.encode(r.password()));
        u = users.save(u);
        log.info("Registered user id={}", u.getId());
        return response(u);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest r) {
        User u = users.findByUsername(r.username().trim().toLowerCase())
                .orElseThrow(() -> ApiException.unauthorized("Invalid username or password"));
        if (!encoder.matches(r.password(), u.getPasswordHash())) throw ApiException.unauthorized("Invalid username or password");
        return response(u);
    }

    private AuthResponse response(User u) {
        return new AuthResponse(jwt.generate(u.getId(), u.getUsername()), u.getId(), u.getUsername(), u.getDisplayName());
    }
}
