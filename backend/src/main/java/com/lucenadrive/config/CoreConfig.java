package com.lucenadrive.config;

import com.lucenadrive.common.crypto.FieldEncryptor;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class CoreConfig {

    private static final Logger log = LoggerFactory.getLogger(CoreConfig.class);

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public FieldEncryptor fieldEncryptor(LucenaProperties props, Environment env) {
        return new FieldEncryptor(keyOrEphemeral(props.security().dataEncryptionKey(), "LUCENA_DATA_ENCRYPTION_KEY", env));
    }

    /**
     * Em "dev"/"test" uma chave efêmera é aceita (dados criptografados se tornam ilegíveis após
     * reiniciar — compatível com o emulador, que também é efêmero). Fora disso, a chave é obrigatória.
     */
    static byte[] keyOrEphemeral(String base64, String envName, Environment env) {
        if (base64 != null && !base64.isBlank()) {
            return Base64.getDecoder().decode(base64.trim());
        }
        if (env.acceptsProfiles(Profiles.of("dev", "test"))) {
            log.warn("{} ausente: usando chave efêmera (somente dev/test).", envName);
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            return key;
        }
        throw new IllegalStateException("Variável obrigatória ausente: " + envName);
    }

    @Bean
    public MfaTokenKey mfaTokenKey(LucenaProperties props, Environment env) {
        return new MfaTokenKey(keyOrEphemeral(props.security().adminMfaTokenSecret(), "LUCENA_ADMIN_MFA_TOKEN_SECRET", env));
    }

    /** Chave HMAC usada para assinar o token de sessão administrativa pós-MFA. */
    public record MfaTokenKey(byte[] value) {
    }
}
