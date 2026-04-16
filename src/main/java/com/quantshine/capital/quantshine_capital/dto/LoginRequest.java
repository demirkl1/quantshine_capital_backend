package com.quantshine.capital.quantshine_capital.dto; // veya controller paketi içine koyabilirsin

import lombok.Data;

@Data
public class LoginRequest {
    private String email;
    private String password;
}