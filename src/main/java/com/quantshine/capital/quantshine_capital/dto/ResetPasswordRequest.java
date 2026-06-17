package com.quantshine.capital.quantshine_capital.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Şifre sıfırlama: kod + yeni şifre ile sıfırlama. */
@Data
public class ResetPasswordRequest {
    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "Kod 6 haneli olmalı")
    private String code;

    @NotBlank
    @Size(min = 8, max = 100, message = "Şifre en az 8 karakter olmalı")
    private String newPassword;
}
