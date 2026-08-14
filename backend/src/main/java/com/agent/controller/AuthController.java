package com.agent.controller;

import com.agent.dto.AuthRequest;
import com.agent.dto.LoginResponse;
import com.agent.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Map<String, String> register(@Valid @RequestBody AuthRequest request) {
        authService.register(request);
        return Map.of("status", "success", "message", "success");
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody AuthRequest request) {
        return authService.login(request);
    }
}
