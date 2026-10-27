package com.lucenadrive.payment.webhook;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint público, autenticado exclusivamente pela assinatura do provedor. */
@RestController
@RequestMapping("/api/v1/webhooks/payments")
@Tag(name = "Webhooks de pagamento")
public class WebhookController {

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    @PostMapping("/{provider}")
    public Map<String, String> receive(@PathVariable String provider,
                                       @RequestHeader(value = "X-Lucena-Signature", required = false) String signature,
                                       @RequestBody byte[] body) {
        return Map.of("result", service.receive(provider, body, signature).name());
    }
}
