package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.security.Role;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

class ReportIT extends IntegrationTestBase {

    private MvcResult get(String path, Actor a) throws Exception {
        var req = MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + a.token());
        if (a.mfa() != null) {
            req.header("X-Lucena-MFA", a.mfa());
        }
        return mvc.perform(req).andReturn();
    }

    @Test
    void ridesAndLedgerCsvForSpreadsheets() throws Exception {
        Actor admin = staffWithMfa(Role.ADMIN);
        Actor support = staffWithMfa(Role.SUPPORT);
        String cityId = seedCityWithFares(admin, "Cidade Relatório");
        Actor driver = approvedOnlineDriver(admin, cityId, LAT, LNG);
        Actor pax = passenger();
        JsonNode est = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "Rua A; 10"),
                "destination", place(LAT + 0.03, LNG, "=HYPERLINK(\"x\")")), 200);
        String rideId = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId",
                est.get("quotes").get(0).get("quoteId").asString(), "paymentMethod", "CASH"), 201, idem()).get("id").asString();
        call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", driver, null, 200);
        driverAt(driver, LAT, LNG);
        for (String s : List.of("DRIVER_ARRIVED", "IN_PROGRESS", "COMPLETED")) {
            call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver, Map.of("status", s), 200);
        }

        MvcResult rides = get("/api/v1/admin/reports/rides.csv?cityId=" + cityId, admin);
        assertThat(rides.getResponse().getStatus()).isEqualTo(200);
        assertThat(rides.getResponse().getHeader("Content-Disposition")).contains("corridas_").contains(".csv");
        String csv = new String(rides.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Código;Solicitada em;");
        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains("COMPLETED").contains("Cidade Relatório - PB").matches(".*;\\d+,\\d{2};.*");
        assertThat(csv).doesNotContain(pax.uid()).doesNotContain("@exemplo.test");

        // suporte vê corridas, mas não lançamentos financeiros
        assertThat(get("/api/v1/admin/reports/rides.csv", support).getResponse().getStatus()).isEqualTo(200);
        assertThat(get("/api/v1/admin/reports/ledger.csv", support).getResponse().getStatus()).isEqualTo(403);
        assertThat(get("/api/v1/admin/reports/ledger.csv", pax).getResponse().getStatus()).isEqualTo(403);
        String ledger = new String(get("/api/v1/admin/reports/ledger.csv", admin).getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(ledger).contains("PLATFORM_COMMISSION").contains(rideId);
        assertThat(get("/api/v1/admin/reports/rides.csv?from=2026-01-10&to=2026-01-01", admin).getResponse().getStatus())
                .isEqualTo(400);
        JsonNode audit = call(HttpMethod.GET, "/api/v1/admin/audit?targetType=report", admin, null, 200);
        assertThat(audit.get("items").valueStream().map(a -> a.get("targetId").asString())).contains("rides", "ledger");
    }
}
