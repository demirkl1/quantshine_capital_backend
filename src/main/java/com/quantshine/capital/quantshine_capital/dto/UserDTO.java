package com.quantshine.capital.quantshine_capital.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class UserDTO {

    @NotBlank(message = "Ad boş olamaz")
    @Size(max = 50, message = "Ad en fazla 50 karakter olabilir")
    private String firstName;

    @NotBlank(message = "Soyad boş olamaz")
    @Size(max = 50, message = "Soyad en fazla 50 karakter olabilir")
    private String lastName;

    @NotBlank(message = "TC kimlik numarası boş olamaz")
    @Pattern(regexp = "^[0-9]{11}$", message = "TC kimlik numarası 11 rakamdan oluşmalıdır")
    private String tcNo;

    @NotBlank(message = "E-posta boş olamaz")
    @Email(message = "Geçerli bir e-posta adresi giriniz")
    @Size(max = 100, message = "E-posta en fazla 100 karakter olabilir")
    private String email;

    @NotBlank(message = "Şifre boş olamaz")
    @Size(min = 8, max = 72, message = "Şifre 8 ile 72 karakter arasında olmalıdır")
    private String password;

    @Pattern(regexp = "^[0-9]{10,11}$", message = "Telefon numarası 10 veya 11 rakamdan oluşmalıdır")
    private String phoneNumber;

    @NotBlank(message = "Rol boş olamaz")
    @Pattern(regexp = "INVESTOR|ADVISOR", message = "Rol INVESTOR veya ADVISOR olmalıdır")
    private String role;
}
