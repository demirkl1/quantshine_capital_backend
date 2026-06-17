package com.quantshine.capital.quantshine_capital.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** İletişim formu mesajı. */
@Data
public class ContactRequest {

    @NotBlank
    @Size(max = 120)
    private String name;

    @NotBlank
    @Email
    @Size(max = 200)
    private String email;

    @Size(max = 40)
    private String phone;

    @NotBlank
    @Size(max = 5000)
    private String message;
}
