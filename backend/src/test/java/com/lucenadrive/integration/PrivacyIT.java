package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.persistence.Collections;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.security.Role;
import com.lucenadrive.support.FakeAuthAdmin;
import com.lucenadrive.user.User;
import com.lucenadrive.user.UserStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;

class PrivacyIT extends IntegrationTestBase {

    @Autowired FakeAuthAdmin authAdmin;

    @Test
    void exportAnonymizationAndSupportAttachments() throws Exception {
        Actor admin = staffWithMfa(Role.ADMIN);
        Actor support = staffWithMfa(Role.SUPPORT);
        String cityId = seedCityWithFares(admin, "Cidade Privacidade");
        Actor driver = approvedOnlineDriver(admin, cityId, LAT, LNG);
        Actor pax = passenger();

        // Corrida concluída com mensagem no chat
        JsonNode est = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "Rua Origem, 10"),
                "destination", place(LAT + 0.03, LNG, "Rua Destino, 20")), 200);
        String rideId = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId",
                est.get("quotes").get(0).get("quoteId").asString(), "paymentMethod", "CASH"), 201, idem()).get("id").asString();
        call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", driver, null, 200);
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/chat/messages", pax,
                Map.of("clientMessageId", UUID.randomUUID().toString(), "text", "Estou no portão azul"), 201);
        driverAt(driver, LAT, LNG);
        for (String s : List.of("DRIVER_ARRIVED", "IN_PROGRESS", "COMPLETED")) {
            call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver, Map.of("status", s), 200);
        }

        // Anexo de suporte: precisa existir no armazenamento, ser do próprio usuário e de tipo permitido
        String okPath = "supportAttachments/" + pax.uid() + "/comprovante.pdf";
        storage.put(okPath, "application/pdf", 2048);
        storage.put("supportAttachments/" + pax.uid() + "/script.html", "text/html", 100);
        Map<String, Object> ticket = Map.of("category", "LOST_ITEM", "rideId", rideId, "subject", "Esqueci a mochila",
                "description", "Mochila preta no banco de trás.");
        call(HttpMethod.POST, "/api/v1/support/tickets", pax, with(ticket, List.of("supportAttachments/" + pax.uid() + "/nao-existe.pdf")), 400);
        call(HttpMethod.POST, "/api/v1/support/tickets", pax, with(ticket, List.of("supportAttachments/" + pax.uid() + "/script.html")), 400);
        call(HttpMethod.POST, "/api/v1/support/tickets", pax, with(ticket, List.of("supportAttachments/" + driver.uid() + "/x.pdf")), 403);
        String ticketId = call(HttpMethod.POST, "/api/v1/support/tickets", pax, with(ticket, List.of(okPath)), 201)
                .get("id").asString();
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/support/tickets/" + ticketId + "/attachments/0")
                        .header("Authorization", "Bearer " + support.token()).header("X-Lucena-MFA", support.mfa()))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.header().string("Content-Type", "application/pdf"))
                .andExpect(MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"));
        call(HttpMethod.GET, "/api/v1/admin/support/tickets/" + ticketId + "/attachments/3", support, null, 404);
        call(HttpMethod.GET, "/api/v1/admin/support/tickets/" + ticketId + "/attachments/0", pax, null, 403);
        JsonNode audit = call(HttpMethod.GET, "/api/v1/admin/audit?targetType=supportTicket&targetId=" + ticketId, admin, null, 200);
        assertThat(audit.get("items").valueStream().map(a -> a.get("action").asString()))
                .contains("SUPPORT_ATTACHMENT_VIEWED");

        // Exportação: exige login recente; não expõe CPF completo nem quem avaliou
        tokens.setStaleLogin(pax.uid(), clock.instant().minus(Duration.ofHours(2)));
        call(HttpMethod.GET, "/api/v1/me/data-export", pax, null, 403);
        tokens.setStaleLogin(pax.uid(), clock.instant());
        JsonNode export = call(HttpMethod.GET, "/api/v1/me/data-export", pax, null, 200);
        assertThat(export.get("account").get("email").asString()).isEqualTo(pax.uid() + "@exemplo.test");
        assertThat(export.get("account").get("cpfMasked").asString()).contains("*");
        assertThat(export.get("ridesAsPassenger").size()).isEqualTo(1);
        assertThat(export.get("chatMessagesSent").get(0).get("text").asString()).isEqualTo("Estou no portão azul");
        assertThat(export.get("supportTickets").size()).isEqualTo(1);
        assertThat(export.toString()).doesNotContain("cpfEncrypted").doesNotContain(driver.uid() + "@");

        JsonNode driverExport = call(HttpMethod.GET, "/api/v1/me/data-export", driver, null, 200);
        assertThat(driverExport.get("driver").get("vehicles").size()).isEqualTo(1);
        assertThat(driverExport.get("ridesAsDriver").size()).isEqualTo(1);
        assertThat(driverExport.get("driver").get("ledger").size()).isPositive();

        // Exclusão: titular solicita; suporte não executa; admin executa com justificativa
        String reqId = call(HttpMethod.POST, "/api/v1/me/privacy-requests", pax,
                Map.of("type", "ACCOUNT_DELETION", "details", "Quero excluir minha conta"), 201).get("id").asString();
        call(HttpMethod.POST, "/api/v1/admin/privacy-requests/" + reqId + "/anonymize", support,
                Map.of("reason", "Solicitação do titular verificada"), 403);
        call(HttpMethod.POST, "/api/v1/admin/privacy-requests/" + reqId + "/anonymize", admin, Map.of("reason", "curta"), 400);
        JsonNode result = call(HttpMethod.POST, "/api/v1/admin/privacy-requests/" + reqId + "/anonymize", admin,
                Map.of("reason", "Solicitação do titular verificada por e-mail"), 200);
        assertThat(result.get("ridesUpdated").asInt()).isEqualTo(1);
        assertThat(result.get("messagesRedacted").asInt()).isEqualTo(1);
        assertThat(result.get("filesDeleted").asInt()).isEqualTo(2);
        call(HttpMethod.POST, "/api/v1/admin/privacy-requests/" + reqId + "/anonymize", admin,
                Map.of("reason", "Solicitação do titular verificada por e-mail"), 409);

        User anon = store.get(Collections.USERS, pax.uid(), User.class).orElseThrow();
        assertThat(anon.status()).isEqualTo(UserStatus.DELETED);
        assertThat(anon.email()).isNull();
        assertThat(anon.cpfMasked()).isNull();
        assertThat(store.get(Collections.USER_PRIVATE, pax.uid(), Map.class)).isEmpty();
        Ride ride = store.get(Collections.RIDES, rideId, Ride.class).orElseThrow();
        assertThat(ride.passenger().name()).isEqualTo("Removido (LGPD)");
        assertThat(ride.origin().address()).isEqualTo("Removido (LGPD)");
        assertThat(ride.fare().totalCents()).isPositive(); // dados financeiros preservados
        assertThat(authAdmin.deleted).contains(pax.uid());
        assertThat(storage.has(okPath)).isFalse();
        JsonNode chat = call(HttpMethod.GET, "/api/v1/rides/" + rideId + "/chat/messages", driver, null, 200);
        assertThat(chat.toString()).doesNotContain("portão azul").contains("[mensagem removida]");
        JsonNode auditUser = call(HttpMethod.GET, "/api/v1/admin/audit?targetType=user&targetId=" + pax.uid(), admin, null, 200);
        assertThat(auditUser.get("items").valueStream().map(a -> a.get("action").asString()))
                .contains("ACCOUNT_ANONYMIZED", "DATA_EXPORTED");

        // Motorista com corrida ativa não pode ser anonimizado
        Actor pax2 = passenger();
        clock.advance(Duration.ofSeconds(5));
        JsonNode est2 = call(HttpMethod.POST, "/api/v1/rides/estimate", pax2, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.02, LNG, "B")), 200);
        call(HttpMethod.POST, "/api/v1/rides", pax2, Map.of("quoteId",
                est2.get("quotes").get(0).get("quoteId").asString(), "paymentMethod", "CASH"), 201, idem());
        String req2 = call(HttpMethod.POST, "/api/v1/me/privacy-requests", pax2,
                Map.of("type", "ACCOUNT_DELETION"), 201).get("id").asString();
        call(HttpMethod.POST, "/api/v1/admin/privacy-requests/" + req2 + "/anonymize", admin,
                Map.of("reason", "Solicitação do titular verificada"), 409);
    }

    private static Map<String, Object> with(Map<String, Object> base, List<String> attachments) {
        Map<String, Object> m = new java.util.HashMap<>(base);
        m.put("attachmentPaths", attachments);
        return m;
    }
}
