package com.chat.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {
    private UserDtos() {}

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,30}", message = "3-30 letters, digits or underscore") String username,
            @Size(max = 60) String displayName,
            @NotBlank @Size(min = 6, max = 100, message = "must be 6-100 characters") String password) {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record AuthResponse(String token, Long userId, String username, String displayName) {}

    public record UserView(Long id, String username, String displayName) {
        public static UserView of(User u) { return new UserView(u.getId(), u.getUsername(), u.getDisplayName()); }
    }
}
