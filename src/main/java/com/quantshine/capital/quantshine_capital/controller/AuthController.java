package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.dto.LoginRequest;
import com.quantshine.capital.quantshine_capital.dto.UserDTO;
import com.quantshine.capital.quantshine_capital.service.LoginRateLimiter;
import com.quantshine.capital.quantshine_capital.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.AccessTokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final LoginRateLimiter loginRateLimiter;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.auth-server-url}")
    private String serverUrl;

    @Value("${keycloak.client-id}")
    private String clientId;

    @PostMapping("/pending/register")
    public ResponseEntity<?> registerPendingUser(@Valid @RequestBody UserDTO userDto) {
        try {
            userService.registerPendingUser(userDto);
            return ResponseEntity.status(HttpStatus.CREATED).body("Kayıt isteği alındı, admin onayı bekleniyor.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Kayıt hatası: " + e.getMessage());
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        String clientIp = getClientIp(request);

        if (!loginRateLimiter.isAllowed(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("Çok fazla hatalı giriş denemesi. 1 dakika sonra tekrar deneyin.");
        }

        try {
            Keycloak userKeycloak = KeycloakBuilder.builder()
                    .serverUrl(serverUrl)
                    .realm(realm)
                    .clientId(clientId)
                    .username(loginRequest.getEmail())
                    .password(loginRequest.getPassword())
                    .grantType(OAuth2Constants.PASSWORD)
                    .build();

            AccessTokenResponse tokenResponse = userKeycloak.tokenManager().getAccessToken();
            loginRateLimiter.onSuccess(clientIp);
            return ResponseEntity.ok(tokenResponse);
        } catch (Exception e) {
            loginRateLimiter.recordFailure(clientIp);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Giriş başarısız: Bilgilerinizi kontrol edin.");
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null) ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
