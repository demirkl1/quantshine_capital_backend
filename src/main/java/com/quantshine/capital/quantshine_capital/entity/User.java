package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "keycloak_id", unique = true)
    private String keycloakId;

    @Column(name = "tc_no", length = 11, unique = true, nullable = false)
    private String tcNo;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(unique = true, nullable = false)
    private String email;

    private String phoneNumber;

    private String password;

    @Column(nullable = false)
    private boolean isApproved = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "managed_fund_code") // Danışmanın yönettiği ana fon
    private String managedFundCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    @Column(columnDefinition = "TEXT") // Uzun yazılar için TEXT tipi iyidir
    private String description;
}