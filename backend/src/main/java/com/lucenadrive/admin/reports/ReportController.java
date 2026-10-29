package com.lucenadrive.admin.reports;

import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reports")
@Tag(name = "Admin · Relatórios")
public class ReportController {

    private static final ZoneId BR = ZoneId.of("America/Sao_Paulo");

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    /** Datas no formato AAAA-MM-DD (inclusivas), no fuso de Brasília. Padrão: últimos 30 dias. */
    private Instant start(LocalDate from) {
        LocalDate d = from != null ? from : LocalDate.ofInstant(reports.now(), BR).minusDays(29);
        return d.atStartOfDay(BR).toInstant();
    }

    private Instant end(LocalDate to) {
        LocalDate d = to != null ? to : LocalDate.ofInstant(reports.now(), BR);
        return d.plusDays(1).atStartOfDay(BR).toInstant();
    }

    private static ResponseEntity<byte[]> file(String name, byte[] body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @GetMapping("/rides.csv")
    @PreAuthorize("hasAnyRole('SUPPORT','FINANCE','ADMIN','OWNER')")
    public ResponseEntity<byte[]> rides(@AuthenticationPrincipal AuthUser staff,
                                        @RequestParam(required = false) LocalDate from,
                                        @RequestParam(required = false) LocalDate to,
                                        @RequestParam(required = false) String cityId) {
        Instant a = start(from), b = end(to);
        return file("corridas_" + LocalDate.ofInstant(a, BR) + "_a_" + LocalDate.ofInstant(b.minus(Duration.ofSeconds(1)), BR) + ".csv",
                reports.rides(staff, a, b, cityId));
    }

    @GetMapping("/ledger.csv")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OWNER')")
    public ResponseEntity<byte[]> ledger(@AuthenticationPrincipal AuthUser staff,
                                         @RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to) {
        Instant a = start(from), b = end(to);
        return file("lancamentos_" + LocalDate.ofInstant(a, BR) + "_a_" + LocalDate.ofInstant(b.minus(Duration.ofSeconds(1)), BR) + ".csv",
                reports.ledger(staff, a, b));
    }
}
