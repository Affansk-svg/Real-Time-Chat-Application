package com.chat.user;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    @Query("select u from User u where lower(u.username) like lower(concat('%', :q, '%')) "
            + "or lower(u.displayName) like lower(concat('%', :q, '%')) order by u.username")
    List<User> search(@Param("q") String q, Pageable pageable);
}
