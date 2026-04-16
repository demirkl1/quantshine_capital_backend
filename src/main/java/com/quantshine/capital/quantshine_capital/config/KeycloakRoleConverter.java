package com.quantshine.capital.quantshine_capital.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        // 'realm_access' claim'ini alıyoruz
        Map<String, Object> realmAccess = (Map<String, Object>) jwt.getClaims().get("realm_access");

        // Eğer realm_access boşsa veya roller yoksa boş liste dön
        if (realmAccess == null || realmAccess.get("roles") == null) {
            return Collections.emptyList();
        }

        // Rolleri al ve başına "ROLE_" ekleyerek Spring Authority nesnelerine çevir
        return ((Collection<String>) realmAccess.get("roles")).stream()
                .map(roleName -> "ROLE_" + roleName)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}