package com.quantshine.capital.quantshine_capital.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Şifre sıfırlama: doğrulama kodu isteği. */
@Data
public class ForgotPasswordRequest {
    @NotBlank
    @Email
    private String email;
}
