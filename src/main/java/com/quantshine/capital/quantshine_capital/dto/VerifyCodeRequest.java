package com.quantshine.capital.quantshine_capital.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Şifre sıfırlama: doğrulama kodu kontrolü. */
@Data
public class VerifyCodeRequest {
    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "Kod 6 haneli olmalı")
    private String code;
}
