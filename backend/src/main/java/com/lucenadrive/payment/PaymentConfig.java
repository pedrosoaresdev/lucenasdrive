package com.lucenadrive.payment;

import com.lucenadrive.config.LucenaProperties;
import com.lucenadrive.payment.asaas.AsaasPaymentProvider;
import com.lucenadrive.persistence.DocStore;
import java.time.Clock;
import tools.jackson.databind.json.JsonMapper;
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
    public PaymentProvider paymentProvider(LucenaProperties props, DocStore store, JsonMapper json, Clock clock) {
        LucenaProperties.Payments p = props.payments();
        if ("asaas".equalsIgnoreCase(p.provider())) {
            if (!p.configured()) {
                log.warn("Asaas selecionado sem PAYMENTS_API_KEY. Pagamentos digitais desabilitados.");
                return new UnconfiguredPaymentProvider();
            }
            boolean production = "production".equalsIgnoreCase(p.environment());
            String url = p.baseUrl() != null && !p.baseUrl().isBlank() ? p.baseUrl()
                    : production ? AsaasPaymentProvider.PRODUCTION_URL : AsaasPaymentProvider.SANDBOX_URL;
            if (p.webhookSecret() == null || p.webhookSecret().isBlank()) {
                log.warn("PAYMENTS_WEBHOOK_SECRET vazio: webhooks do Asaas serão recusados; a confirmação virá só pela conciliação ativa.");
            }
            log.info("Pagamentos: Asaas ({})", production ? "PRODUÇÃO" : "sandbox");
            return new AsaasPaymentProvider(url, p.apiKey(), store, json, clock);
        }
        if (!"none".equalsIgnoreCase(props.payments().provider())) {
            log.warn("Provedor '{}' informado, mas nenhum adaptador implementado. Pagamentos digitais desabilitados.",
                    props.payments().provider());
        }
        return new UnconfiguredPaymentProvider();
    }
}
