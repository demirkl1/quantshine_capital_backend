package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.entity.User;
import com.quantshine.capital.quantshine_capital.entity.Role;
import com.quantshine.capital.quantshine_capital.repository.UserRepository;
import com.quantshine.capital.quantshine_capital.repository.InvestmentRepository;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import com.quantshine.capital.quantshine_capital.dto.UserDTO;
import org.keycloak.representations.idm.RoleRepresentation;

import jakarta.ws.rs.core.Response;
import java.util.Collections;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final InvestmentRepository investmentRepository;
    private final Keycloak keycloak;
    private final EncryptionService encryptionService;
    private final TransactionTemplate transactionTemplate;

    @Value("${keycloak.realm}")
    private String realmName;

    /**
     * 1. Onay Motoru: Yönetici onay verdiğinde kullanıcıyı Keycloak'a aktarır.
     *
     * Yapı:
     *   ① Kısa DB oku → bağlantı hemen bırakılır
     *   ② Keycloak REST çağrıları → DB bağlantısı tutulmaz
     *   ③ Kısa DB yaz → yeni kısa transaction
     *
     * Önceki @Transactional yaklaşımı tüm Keycloak ağ çağrıları boyunca
     * bir DB bağlantısını açık tutuyordu; bu HikariCP havuzunu tıkıyordu.
     */
    public void approveUser(Long userId) {
        // ① Kullanıcıyı kısa bir transaction ile oku
        User user = transactionTemplate.execute(status ->
                userRepository.findById(userId)
                        .orElseThrow(() -> new RuntimeException("Kullanıcı bulunamadı!")));

        if (user.isApproved()) {
            throw new RuntimeException("Bu kullanıcı zaten onaylanmış.");
        }
        if (user.getPassword() == null) {
            throw new RuntimeException("Kullanıcının şifresi DB'de bulunamadı.");
        }

        // ② Keycloak REST çağrıları — transaction dışında (DB bağlantısı yok)
        String plainPassword = encryptionService.decrypt(user.getPassword());
        String keycloakId    = provisionKeycloakUser(user, plainPassword);

        // ③ DB'yi kısa bir transaction ile güncelle
        transactionTemplate.execute(status -> {
            User fresh = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("Kullanıcı bulunamadı!"));
            fresh.setKeycloakId(keycloakId);
            fresh.setApproved(true);
            fresh.setPassword(null);
            return userRepository.save(fresh);
        });

        log.info("{} için Keycloak hesabı ve {} rolü başarıyla oluşturuldu.",
                user.getEmail(), user.getRole().name());
    }

    /**
     * Keycloak'ta kullanıcı oluşturur, şifresini tanımlar, rolünü atar.
     * Herhangi bir DB transaction'ı tutmadan çalışır.
     *
     * @return oluşturulan Keycloak kullanıcı ID'si
     */
    private String provisionKeycloakUser(User user, String plainPassword) {
        try {
            RealmResource   realmResource = keycloak.realm(realmName);
            UsersResource   usersResource = realmResource.users();

            UserRepresentation kcUser = new UserRepresentation();
            kcUser.setUsername(user.getEmail());
            kcUser.setEmail(user.getEmail());
            kcUser.setFirstName(user.getFirstName());
            kcUser.setLastName(user.getLastName());
            kcUser.setEnabled(true);
            kcUser.setEmailVerified(true);

            Response response = usersResource.create(kcUser);

            if (response.getStatus() == 409) {
                throw new RuntimeException(
                        "Bu e-posta adresi Keycloak'ta zaten mevcut: " + user.getEmail());
            }
            if (response.getStatus() != 201) {
                String detail = response.readEntity(String.class);
                throw new RuntimeException(
                        "Keycloak kullanıcı oluşturma hatası (" + response.getStatus() + "): " + detail);
            }

            String keycloakId = CreatedResponseUtil.getCreatedId(response);

            // Şifre ata
            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setTemporary(false);
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue(plainPassword);
            usersResource.get(keycloakId).resetPassword(cred);

            // Realm rolü ata
            String roleName = user.getRole().name();
            RoleRepresentation realmRole;
            try {
                realmRole = realmResource.roles().get(roleName).toRepresentation();
            } catch (jakarta.ws.rs.NotFoundException e) {
                throw new RuntimeException(
                        "Keycloak realm'ında '" + roleName + "' rolü bulunamadı. " +
                        "Keycloak admin panelinden Realm Roles altına '" + roleName + "' ekleyin.");
            }
            usersResource.get(keycloakId).roles().realmLevel().add(Collections.singletonList(realmRole));

            return keycloakId;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Keycloak bağlantı hatası: " + e.getMessage() +
                    ". Keycloak'ın çalıştığından ve admin kimlik bilgilerinin doğru olduğundan emin olun.");
        }
    }
    /**
     * JWT → User stub → senkronizasyon. `/users/me` çağrılmadan önce trade vb.
     * endpointler de kullanıcıyı local DB'ye yansıtabilsin diye tek giriş noktası.
     */
    @Transactional
    public User ensureSyncedFromJwt(org.springframework.security.oauth2.jwt.Jwt jwt) {
        User u = new User();
        u.setKeycloakId(jwt.getSubject());
        String email = jwt.getClaimAsString("email");
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        u.setEmail(email != null ? email
                : (preferredUsername != null ? preferredUsername : jwt.getSubject()) + "@local");

        // Keycloak'tan given_name/family_name gelmeyebilir (ör. manuel oluşturulmuş admin).
        // DB'de first_name/last_name NOT NULL olduğu için her durumda dolu olmalı.
        String given = jwt.getClaimAsString("given_name");
        String family = jwt.getClaimAsString("family_name");
        String name = jwt.getClaimAsString("name");
        if ((given == null || given.isBlank()) && name != null && !name.isBlank()) {
            String[] parts = name.trim().split("\\s+", 2);
            given = parts[0];
            if ((family == null || family.isBlank()) && parts.length > 1) family = parts[1];
        }
        if (given == null || given.isBlank()) {
            given = preferredUsername != null && !preferredUsername.isBlank()
                    ? preferredUsername
                    : (email != null && email.contains("@") ? email.substring(0, email.indexOf('@')) : "User");
        }
        if (family == null || family.isBlank()) family = "-";
        u.setFirstName(given);
        u.setLastName(family);
        try {
            java.util.Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null) {
                Object rolesObj = realmAccess.get("roles");
                if (rolesObj instanceof List<?> roles) {
                    if (roles.contains("ADMIN"))         u.setRole(Role.ADMIN);
                    else if (roles.contains("ADVISOR"))  u.setRole(Role.ADVISOR);
                    else                                 u.setRole(Role.INVESTOR);
                }
            }
        } catch (Exception ignored) { /* rol okunamazsa syncUserWithIdp varsayılanı kullanır */ }
        return syncUserWithIdp(u);
    }

    /**
     * 2. Senkronizasyon Motoru: Giriş anında Keycloak verilerini DB ile eşler.
     */
    @Transactional
    public User syncUserWithIdp(User userFromIdp) {
        return userRepository.findByKeycloakId(userFromIdp.getKeycloakId())
                .map(existingUser -> {
                    // IDP'den gelen güncel bilgileri yansıt
                    existingUser.setEmail(userFromIdp.getEmail());
                    if (userFromIdp.getFirstName() != null) existingUser.setFirstName(userFromIdp.getFirstName());
                    if (userFromIdp.getLastName() != null) existingUser.setLastName(userFromIdp.getLastName());

                    // Giriş yapabildiyse onaylıdır
                    existingUser.setApproved(true);
                    existingUser.setPassword(null);

                    return userRepository.save(existingUser);
                })
                .orElseGet(() -> {
                    // DB'de yoksa (manuel Keycloak kaydı gibi) yeni oluştur
                    if (userFromIdp.getRole() == null) {
                        userFromIdp.setRole(Role.INVESTOR);
                    }
                    // tcNo NOT NULL constraint — IDP kullanıcıları için benzersiz placeholder üret
                    if (userFromIdp.getTcNo() == null) {
                        String kc = userFromIdp.getKeycloakId().replace("-", "");
                        userFromIdp.setTcNo(kc.substring(0, Math.min(11, kc.length())));
                    }
                    // first_name / last_name NOT NULL — IDP claim'i yoksa placeholder
                    if (userFromIdp.getFirstName() == null || userFromIdp.getFirstName().isBlank()) {
                        userFromIdp.setFirstName("User");
                    }
                    if (userFromIdp.getLastName() == null || userFromIdp.getLastName().isBlank()) {
                        userFromIdp.setLastName("-");
                    }
                    if (userFromIdp.getEmail() == null || userFromIdp.getEmail().isBlank()) {
                        userFromIdp.setEmail(userFromIdp.getKeycloakId() + "@local");
                    }
                    userFromIdp.setApproved(true);
                    userFromIdp.setPassword(null);
                    return userRepository.save(userFromIdp);
                });
    }
    public List<User> getPendingApprovals() {
        return userRepository.findByIsApprovedFalse();
    }

    public void registerPendingUser(UserDTO userDto) {
        if (userRepository.findByEmail(userDto.getEmail()).isPresent()) {
            throw new RuntimeException("Bu e-posta adresi zaten kayıtlı.");
        }
        if (userRepository.findByTcNo(userDto.getTcNo()).isPresent()) {
            throw new RuntimeException("Bu TC Kimlik Numarası zaten kayıtlı.");
        }

        User user = new User();
        user.setFirstName(userDto.getFirstName());
        user.setLastName(userDto.getLastName());
        user.setEmail(userDto.getEmail());
        user.setTcNo(userDto.getTcNo());
        user.setPhoneNumber(userDto.getPhoneNumber());
        user.setPassword(encryptionService.encrypt(userDto.getPassword()));
        user.setRole(Role.valueOf(userDto.getRole()));
        user.setApproved(false);

        userRepository.save(user);
    }
    public List<User> getApprovedInvestors() {
        return userRepository.findByRoleAndIsApprovedTrue(Role.INVESTOR);
    }

    public List<User> getAdvisorsAndAdmins() {
        return userRepository.findAllByRoleIn(List.of(Role.ADMIN, Role.ADVISOR));
    }

    /**
     * Yatırımcıyı DB'den ve Keycloak'tan kalıcı olarak siler.
     * Yatırım kayıtları da temizlenir; işlem geçmişi korunur.
     *
     * Keycloak silme işlemi DB transaction'ı commit olduktan SONRA yapılır.
     * Böylece Keycloak yavaşlığı DB bağlantısını bloklamaz.
     */
    public void deleteInvestorByTcNo(String tcNo) {
        // 1. DB işlemleri — kısa transaction
        String[] keycloakIdHolder = new String[1];
        String[] emailHolder = new String[1];

        transactionTemplate.execute(status -> {
            User user = userRepository.findByTcNo(tcNo)
                    .orElseThrow(() -> new RuntimeException("TC No ile kullanıcı bulunamadı: " + tcNo));

            if (user.getRole() != Role.INVESTOR) {
                throw new RuntimeException("Yalnızca yatırımcı hesapları bu yolla silinemez.");
            }

            investmentRepository.deleteByInvestorId(user.getId());
            userRepository.delete(user);

            keycloakIdHolder[0] = user.getKeycloakId();
            emailHolder[0]      = user.getEmail();
            return null;
        });

        log.info("Yatırımcı DB'den silindi: {} ({})", emailHolder[0], tcNo);

        // 2. Keycloak silme — DB transaction kapatıldıktan sonra (bağlantı serbest)
        if (keycloakIdHolder[0] != null) {
            try {
                keycloak.realm(realmName).users().delete(keycloakIdHolder[0]);
                log.info("Keycloak hesabı silindi: {}", keycloakIdHolder[0]);
            } catch (Exception e) {
                log.warn("Keycloak hesabı silinemedi (DB silindi, devam ediliyor): {}", e.getMessage());
            }
        }
    }

}