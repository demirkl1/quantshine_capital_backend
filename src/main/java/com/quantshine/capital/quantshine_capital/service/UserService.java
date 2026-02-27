package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.entity.User;
import com.quantshine.capital.quantshine_capital.entity.Role;
import com.quantshine.capital.quantshine_capital.repository.UserRepository;
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
    private final Keycloak keycloak;
    private final EncryptionService encryptionService;

    @Value("${keycloak.realm}")
    private String realmName;

    /**
     * 1. Onay Motoru: Yönetici onay verdiğinde kullanıcıyı Keycloak'a aktarır.
     */
    @Transactional
    public void approveUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Kullanıcı bulunamadı!"));

        if (user.isApproved()) {
            throw new RuntimeException("Bu kullanıcı zaten onaylanmış.");
        }

        UserRepresentation kcUser = new UserRepresentation();
        kcUser.setUsername(user.getEmail());
        kcUser.setEmail(user.getEmail());
        kcUser.setFirstName(user.getFirstName());
        kcUser.setLastName(user.getLastName());
        kcUser.setEnabled(true);
        kcUser.setEmailVerified(true);

        try {
            RealmResource realmResource = keycloak.realm(realmName);
            UsersResource usersResource = realmResource.users();

            Response response = usersResource.create(kcUser);

            if (response.getStatus() == 409) {
                throw new RuntimeException("Bu e-posta adresi Keycloak'ta zaten mevcut: " + user.getEmail());
            }

            if (response.getStatus() != 201) {
                String errorDetail = response.readEntity(String.class);
                throw new RuntimeException("Keycloak kullanıcı oluşturma hatası (" + response.getStatus() + "): " + errorDetail);
            }

            String keycloakId = CreatedResponseUtil.getCreatedId(response);

            // 1. Şifre tanımla
            if (user.getPassword() == null) {
                throw new RuntimeException("Kullanıcının şifresi DB'de bulunamadı.");
            }
            String plainPassword = encryptionService.decrypt(user.getPassword());
            CredentialRepresentation passwordCred = new CredentialRepresentation();
            passwordCred.setTemporary(false);
            passwordCred.setType(CredentialRepresentation.PASSWORD);
            passwordCred.setValue(plainPassword);
            usersResource.get(keycloakId).resetPassword(passwordCred);

            // 2. Rol ata — Keycloak realm'ında INVESTOR/ADVISOR rolü tanımlı olmalıdır
            String roleName = user.getRole().name();
            RoleRepresentation realmRole;
            try {
                realmRole = realmResource.roles().get(roleName).toRepresentation();
            } catch (jakarta.ws.rs.NotFoundException e) {
                throw new RuntimeException(
                    "Keycloak realm'ında '" + roleName + "' rolü bulunamadı. " +
                    "Keycloak admin panelinden Realm Roles altına '" + roleName + "' rolünü ekleyin."
                );
            }
            usersResource.get(keycloakId).roles().realmLevel().add(Collections.singletonList(realmRole));

            // 3. DB güncelle
            user.setKeycloakId(keycloakId);
            user.setApproved(true);
            user.setPassword(null);
            userRepository.save(user);

            log.info("{} için Keycloak hesabı ve {} rolü başarıyla oluşturuldu.", user.getEmail(), roleName);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Keycloak bağlantı hatası: " + e.getMessage() +
                ". Keycloak'ın çalıştığından ve admin kimlik bilgilerinin doğru olduğundan emin olun.");
        }
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

}