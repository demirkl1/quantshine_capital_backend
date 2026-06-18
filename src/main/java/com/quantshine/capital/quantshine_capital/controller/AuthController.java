package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.config.AuthCookieService;
import com.quantshine.capital.quantshine_capital.dto.ForgotPasswordRequest;
import com.quantshine.capital.quantshine_capital.dto.LoginRequest;
import com.quantshine.capital.quantshine_capital.dto.ResetPasswordRequest;
import com.quantshine.capital.quantshine_capital.dto.UserDTO;
import com.quantshine.capital.quantshine_capital.dto.VerifyCodeRequest;
import com.quantshine.capital.quantshine_capital.entity.User;
import com.quantshine.capital.quantshine_capital.service.AuditService;
import com.quantshine.capital.quantshine_capital.service.LoginRateLimiter;
import com.quantshine.capital.quantshine_capital.service.PasswordResetService;
import com.quantshine.capital.quantshine_capital.service.RegistrationVerificationService;
import com.quantshine.capital.quantshine_capital.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.AccessTokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final LoginRateLimiter loginRateLimiter;
    private final AuthCookieService authCookies;
    private final JwtDecoder jwtDecoder;
    private final PasswordResetService passwordResetService;
    private final RegistrationVerificationService registrationVerification;
    private final AuditService auditService;

    private final RestClient restClient = RestClient.create();

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.auth-server-url}")
    private String serverUrl;

    @Value("${keycloak.client-id}")
    private String clientId;

    // Kayıt e-posta doğrulaması — adım 1: e-postaya kod gönder (zaten kayıtlıysa sessiz).
    @PostMapping("/register/send-code")
    public ResponseEntity<?> sendRegistrationCode(@Valid @RequestBody ForgotPasswordRequest req) {
        registrationVerification.sendCode(req.getEmail());
        return ResponseEntity.ok("Doğrulama kodu e-posta adresinize gönderildi (adres uygunsa).");
    }

    @PostMapping("/pending/register")
    public ResponseEntity<?> registerPendingUser(@Valid @RequestBody UserDTO userDto) {
        // Kayıt e-posta doğrulaması: kod geçerli değilse kaydı oluşturma.
        if (!registrationVerification.verify(userDto.getEmail(), userDto.getVerificationCode())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("E-posta doğrulama kodu geçersiz veya süresi dolmuş. Lütfen kodu kontrol edin.");
        }
        try {
            userService.registerPendingUser(userDto);
            registrationVerification.consume(userDto.getEmail());
            return ResponseEntity.status(HttpStatus.CREATED).body("Kayıt isteği alındı, admin onayı bekleniyor.");
        } catch (Exception e) {
            // Ayrıntıyı yalnızca logla; istemciye generic mesaj — kullanıcı/e-posta
            // enumerasyonunu önler (örn. "e-posta zaten mevcut" sızdırmaz).
            log.warn("Kayıt başarısız: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Kayıt işlemi tamamlanamadı. Bilgilerinizi kontrol edip tekrar deneyin.");
        }
    }

    // ── Şifremi unuttum akışı ───────────────────────────────────────────────
    // Adım 1: e-posta → doğrulama kodu gönder. Enumeration'a karşı her zaman 200.
    @PostMapping("/password/forgot")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        passwordResetService.requestReset(req.getEmail());
        return ResponseEntity.ok("E-posta adresi kayıtlıysa doğrulama kodu gönderildi.");
    }

    // Adım 2: kodu doğrula (tüketmez) — frontend şifre alanlarını göstermeden önce.
    @PostMapping("/password/verify")
    public ResponseEntity<?> verifyResetCode(@Valid @RequestBody VerifyCodeRequest req) {
        if (passwordResetService.verify(req.getEmail(), req.getCode())) {
            return ResponseEntity.ok("Kod doğrulandı.");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Doğrulama kodu geçersiz veya süresi dolmuş.");
    }

    // Adım 3: kod + yeni şifre → Keycloak şifresini sıfırla.
    @PostMapping("/password/reset")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        try {
            passwordResetService.reset(req.getEmail(), req.getCode(), req.getNewPassword());
            return ResponseEntity.ok("Şifreniz başarıyla güncellendi. Yeni şifrenizle giriş yapabilirsiniz.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            log.warn("Şifre sıfırlama başarısız: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Şifre güncellenemedi. Kodun geçerli ve yeni şifrenin kurallara uygun olduğundan emin olun.");
        }
    }

    /**
     * ROPC ile giriş. Token'lar artık yanıt gövdesinde DÖNMEZ; HttpOnly cookie'lere
     * yazılır (XSS sızıntısını önler). Gövdede yalnızca yönlendirme için kullanıcı
     * profili (rol vb.) döner — hassas değildir.
     */
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
            auditService.log("LOGIN_SUCCESS", "email=" + AuditService.maskEmail(loginRequest.getEmail()) + " ip=" + clientIp);

            // Access token'ı doğrula + claim'lerden kullanıcıyı DB ile senkronla.
            Jwt jwt = jwtDecoder.decode(tokenResponse.getToken());
            User user = userService.ensureSyncedFromJwt(jwt);

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE,
                            authCookies.accessCookie(tokenResponse.getToken(), tokenResponse.getExpiresIn()).toString())
                    .header(HttpHeaders.SET_COOKIE,
                            authCookies.refreshCookie(tokenResponse.getRefreshToken(), tokenResponse.getRefreshExpiresIn()).toString())
                    .body(user);
        } catch (Exception e) {
            loginRateLimiter.recordFailure(clientIp);
            auditService.log("LOGIN_FAILURE", "email=" + AuditService.maskEmail(loginRequest.getEmail()) + " ip=" + clientIp);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Giriş başarısız: Bilgilerinizi kontrol edin.");
        }
    }

    /**
     * Refresh cookie'sini kullanarak access token'ı şeffaf yeniler ve cookie'leri
     * günceller. Frontend bunu 401 sonrası bir kez çağırır.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        String refreshToken = AuthCookieService.readCookie(request, AuthCookieService.REFRESH_COOKIE);
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add(OAuth2Constants.GRANT_TYPE, OAuth2Constants.REFRESH_TOKEN);
            form.add(OAuth2Constants.CLIENT_ID, clientId);
            form.add(OAuth2Constants.REFRESH_TOKEN, refreshToken);

            AccessTokenResponse refreshed = restClient.post()
                    .uri(serverUrl + "/realms/" + realm + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(AccessTokenResponse.class);

            if (refreshed == null || refreshed.getToken() == null) {
                return clearCookiesUnauthorized();
            }

            ResponseEntity.BodyBuilder ok = ResponseEntity.status(HttpStatus.NO_CONTENT);
            ok.header(HttpHeaders.SET_COOKIE,
                    authCookies.accessCookie(refreshed.getToken(), refreshed.getExpiresIn()).toString());
            if (refreshed.getRefreshToken() != null) {
                ok.header(HttpHeaders.SET_COOKIE,
                        authCookies.refreshCookie(refreshed.getRefreshToken(), refreshed.getRefreshExpiresIn()).toString());
            }
            return ok.build();
        } catch (Exception e) {
            log.debug("Token yenileme başarısız: {}", e.getMessage());
            return clearCookiesUnauthorized();
        }
    }

    /**
     * Çıkış: cookie'leri temizler ve mümkünse refresh token'ı Keycloak'ta iptal eder.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String refreshToken = AuthCookieService.readCookie(request, AuthCookieService.REFRESH_COOKIE);
        if (refreshToken != null) {
            try {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add(OAuth2Constants.CLIENT_ID, clientId);
                form.add(OAuth2Constants.REFRESH_TOKEN, refreshToken);
                restClient.post()
                        .uri(serverUrl + "/realms/" + realm + "/protocol/openid-connect/logout")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.debug("Keycloak logout (revoke) başarısız, cookie'ler yine de temizleniyor: {}", e.getMessage());
            }
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookies.clearAccessCookie().toString())
                .header(HttpHeaders.SET_COOKIE, authCookies.clearRefreshCookie().toString())
                .build();
    }

    private ResponseEntity<Void> clearCookiesUnauthorized() {
        ResponseCookie clearAccess = authCookies.clearAccessCookie();
        ResponseCookie clearRefresh = authCookies.clearRefreshCookie();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, clearAccess.toString())
                .header(HttpHeaders.SET_COOKIE, clearRefresh.toString())
                .build();
    }

    private String getClientIp(HttpServletRequest request) {
        // X-Forwarded-For istemci tarafından sahte gönderilebilir; güvenilir tek
        // değer, önümüzdeki nginx'in EKLEDİĞİ SON adrestir. İlk değeri almak
        // (eski davranış) saldırganın her istekte yeni "IP" üreterek rate-limit'i
        // atlamasına izin verirdi.
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
