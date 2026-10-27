package com.lucenadrive.payment;

import com.lucenadrive.config.LucenaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    /**
     * Ponto de extensão: ao contratar um provedor, registre aqui o adaptador correspondente
     * (ex.: "asaas", "pagarme", "mercadopago") conforme lucena.payments.provider.
     */
    @Bean
    @ConditionalOnMissingBean
    public PaymentProvider paymentProvider(LucenaProperties props) {
        if (!"none".equalsIgnoreCase(props.payments().provider())) {
            log.warn("Provedor '{}' informado, mas nenhum adaptador implementado. Pagamentos digitais desabilitados.",
                    props.payments().provider());
        }
        return new UnconfiguredPaymentProvider();
    }
}
