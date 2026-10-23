package com.chat.user;

import com.chat.chat.PresenceService;
import com.chat.common.ApiException;
import com.chat.security.AuthUser;
import com.chat.user.UserDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class UserController {
    private final AuthService auth;
    private final UserRepository users;
    private final PresenceService presence;

    @PostMapping("/api/auth/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest r) {
        return ResponseEntity.status(201).body(auth.register(r));
    }

    @PostMapping("/api/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest r) { return auth.login(r); }

    @GetMapping("/api/users/me")
    public UserView me(@AuthenticationPrincipal AuthUser u) {
        return users.findById(u.id()).map(UserView::of).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    @GetMapping("/api/users")
    public List<UserView> search(@AuthenticationPrincipal AuthUser u, @RequestParam(defaultValue = "") String q) {
        return users.search(q.trim(), PageRequest.of(0, 20)).stream()
                .filter(x -> !x.getId().equals(u.id())).map(UserView::of).toList();
    }

    @GetMapping("/api/users/online")
    public Set<Long> online() { return presence.online(); }
}
