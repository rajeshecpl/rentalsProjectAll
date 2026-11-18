package com.rentopia.auth.controller;

import com.rentopia.auth.dto.AuthDtos.AuthResponse;
import com.rentopia.auth.dto.AuthDtos.LoginRequest;
import com.rentopia.auth.dto.AuthDtos.OtpSentResponse;
import com.rentopia.auth.dto.AuthDtos.RefreshRequest;
import com.rentopia.auth.dto.AuthDtos.RegisterRequest;
import com.rentopia.auth.dto.AuthDtos.SendOtpRequest;
import com.rentopia.auth.dto.AuthDtos.VerifyOtpRequest;
import com.rentopia.auth.service.AuthService;
import com.rentopia.auth.service.OtpService;
import com.rentopia.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final OtpService otpService;

    public AuthController(AuthService authService, OtpService otpService) {
        this.authService = authService;
        this.otpService = otpService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a customer or vendor account")
    public AuthResponse register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        return authService.register(req, http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with email and password")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return authService.login(req, http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for new tokens (the old refresh token is revoked)")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
        return authService.refresh(req.refreshToken(), http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a refresh token")
    public void logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
    }

    @PostMapping("/otp/send")
    @Operation(summary = "Send a verification code to the signed-in user's phone or email")
    public OtpSentResponse sendOtp(@Valid @RequestBody SendOtpRequest req) {
        return otpService.send(CurrentUser.id(), req.purpose());
    }

    @PostMapping("/verify-otp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Verify a code and mark the phone or email as verified")
    public void verifyOtp(@Valid @RequestBody VerifyOtpRequest req) {
        otpService.verify(CurrentUser.id(), req.purpose(), req.code());
    }
}
