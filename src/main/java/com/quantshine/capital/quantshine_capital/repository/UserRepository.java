package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.User;
import com.quantshine.capital.quantshine_capital.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByKeycloakId(String keycloakId);

    Optional<User> findByEmail(String email);

    Optional<User> findByTcNo(String tcNo);

    @Query("SELECT u FROM User u WHERE u.isApproved = false")
    List<User> findByIsApprovedFalse();

    @Query("SELECT u FROM User u WHERE u.role = :role AND u.isApproved = true")
    List<User> findByRoleAndIsApprovedTrue(@Param("role") Role role);

    List<User> findAllByRoleIn(List<Role> roles);

    @Query("SELECT u FROM User u WHERE u.role = :role AND u.isApproved = true")
    List<User> findAdvisorsWithInvestors(@Param("role") Role role);
}