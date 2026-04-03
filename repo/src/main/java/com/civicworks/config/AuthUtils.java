package com.civicworks.config;

import com.civicworks.domain.entity.User;
import com.civicworks.exception.BusinessException;
import com.civicworks.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class AuthUtils {

    private final UserRepository userRepository;

    public AuthUtils(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User resolveUser(Authentication authentication) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("Authenticated user not found: " + username, HttpStatus.UNAUTHORIZED));
    }
}
