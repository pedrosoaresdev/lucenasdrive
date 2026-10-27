package com.lucenadrive.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI lucenaOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Lucena Drive API").version("v1")
                        .description("API REST do Lucena Drive. Autenticação: ID token do Firebase (Bearer). "
                                + "Rotas /api/v1/admin/** exigem também o cabeçalho X-Lucena-MFA (sessão pós-TOTP)."))
                .components(new Components()
                        .addSecuritySchemes("firebase", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("adminMfa", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Lucena-MFA")))
                .addSecurityItem(new SecurityRequirement().addList("firebase"));
    }
}
