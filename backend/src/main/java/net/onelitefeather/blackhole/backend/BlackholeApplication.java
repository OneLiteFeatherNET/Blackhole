package net.onelitefeather.blackhole.backend;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@ConfigurationProperties("application.properties")
@OpenAPIDefinition(
        info = @Info(
                title = "Blackhole API",
                version = "0.0.26",
                description = "Simple ban management system",
                license = @License(name = "Close Source"),
                contact = @Contact(
                        url = "https://onelitefeather.net",
                        name = "Management",
                        email = "admin@onelitefeather.net"
                )
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Microsoft Entra ID access token for this API"
)
public final class BlackholeApplication {

    public static void main(String[] args) {
        Micronaut.run(BlackholeApplication.class, args);
    }
}
