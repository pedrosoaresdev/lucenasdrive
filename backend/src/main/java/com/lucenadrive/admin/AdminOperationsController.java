package com.lucenadrive.admin;

import com.lucenadrive.admin.security.SecurityEvent;
import com.lucenadrive.audit.AuditLog;
import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.web.PageResponse;
import com.lucenadrive.finance.FinanceService;
import com.lucenadrive.ledger.LedgerEntry;
import com.lucenadrive.payout.PayoutRequest;
import com.lucenadrive.payout.PayoutService;
import com.lucenadrive.payout.PixKey;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.rating.Rating;
import com.lucenadrive.rating.RatingService;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.support.SupportService;
import com.lucenadrive.support.SupportTicket;
import com.lucenadrive.support.TicketMessage;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Financeiro, avaliações, suporte e auditoria. */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin · Financeiro, moderação, suporte e auditoria")
public class AdminOperationsController {

    private final FinanceService finance;
    private final PayoutService payouts;
    private final RatingService ratings;
    private final SupportService support;
    private final AuditService audit;
    private final DocStore store;

    public AdminOperationsController(FinanceService finance, PayoutService payouts, RatingService ratings,
                                     SupportService support, AuditService audit, DocStore store) {
        this.finance = finance;
        this.payouts = payouts;
        this.ratings = ratings;
        this.support = support;
        this.audit = audit;
        this.store = store;
    }

    public record ModerationRequest(@NotBlank @Pattern(regexp = "HIDE_COMMENT|INVALIDATE|RESTORE|DISMISS_REPORT") String action,
                                    @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record TicketUpdate(SupportTicket.Status status, SupportTicket.Priority priority, String assigneeId,
                               @Size(max = 500) String note) {
    }

    public record ReplyRequest(@NotBlank @Size(max = 2000) String text) {
    }

    public record PixKeyRequest(@NotNull PixKey.KeyType keyType, @NotBlank @Size(max = 80) String key) {
    }

    public record WithdrawRequest(@Min(1) long amountCents) {
    }

    // ------------------------------------------------------------ financeiro

    @GetMapping("/finance/summary")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OWNER')")
    public FinanceService.PlatformFinance summary(@RequestParam(required = false) Instant from,
                                                  @RequestParam(required = false) Instant to) {
        return finance.platform(from != null ? from : Instant.EPOCH, to != null ? to : Instant.now().plusSeconds(1));
    }

    @GetMapping("/finance/statement")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OWNER')")
    public List<LedgerEntry> statement(@RequestParam(defaultValue = "PLATFORM") String account,
                                       @RequestParam(required = false) Instant from,
                                       @RequestParam(required = false) Instant to,
                                       @RequestParam(defaultValue = "200") int limit) {
        return finance.statement(account, from, to, limit);
    }

    /** Exportação CSV do extrato (somente perfis financeiros). Acesso auditado. */
    @GetMapping(value = "/finance/statement.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('FINANCE','OWNER')")
    public ResponseEntity<byte[]> statementCsv(@AuthenticationPrincipal AuthUser staff,
                                               @RequestParam(defaultValue = "PLATFORM") String account,
                                               @RequestParam(required = false) Instant from,
                                               @RequestParam(required = false) Instant to) {
        StringBuilder sb = new StringBuilder("id;data;tipo;conta;direcao;valor_centavos;status;corrida;referencia_externa\n");
        for (LedgerEntry e : finance.statement(account, from, to, 1000)) {
            sb.append(String.join(";", e.id(), e.createdAt().toString(), e.type().name(), e.account(),
                    e.direction().name(), String.valueOf(e.amountCents()), e.status().name(),
                    e.rideId() == null ? "" : e.rideId(), e.externalRef() == null ? "" : e.externalRef())).append('\n');
        }
        audit.record(staff, "FINANCE_STATEMENT_EXPORTED", "ledger", account, null, null, null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=extrato-lucena.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/finance/reconciliation")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OWNER')")
    public List<FinanceService.ReconciliationIssue> reconciliation() {
        return finance.reconciliation();
    }

    @GetMapping("/finance/payouts")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OWNER')")
    public List<PayoutRequest> allPayouts(@RequestParam(required = false) PayoutRequest.Status status) {
        return payouts.listAll(status, 200);
    }

    @GetMapping("/finance/pix-key")
    @PreAuthorize("hasAnyRole('FINANCE','OWNER')")
    public ResponseEntity<PayoutService.PixKeyView> platformPixKey() {
        return payouts.pixKey(PayoutService.PLATFORM_OWNER).map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/finance/pix-key")
    @PreAuthorize("hasRole('OWNER')")
    public PayoutService.PixKeyView setPlatformPixKey(@AuthenticationPrincipal AuthUser staff,
                                                      @Valid @RequestBody PixKeyRequest body) {
        return payouts.setPlatformPixKey(staff, body.keyType(), body.key());
    }

    @PostMapping("/finance/withdrawals")
    @PreAuthorize("hasAnyRole('FINANCE','OWNER')")
    public PayoutRequest withdraw(@AuthenticationPrincipal AuthUser staff,
                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                  @Valid @RequestBody WithdrawRequest body) {
        return payouts.requestPlatformWithdrawal(staff, body.amountCents(), idempotencyKey);
    }

    @PostMapping("/finance/withdrawals/{id}/cancel")
    @PreAuthorize("hasAnyRole('FINANCE','OWNER')")
    public PayoutRequest cancelWithdrawal(@AuthenticationPrincipal AuthUser staff, @PathVariable String id) {
        return payouts.cancel(staff, id);
    }

    // ------------------------------------------------------------ avaliações

    @GetMapping("/ratings")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public List<Rating> ratings(@RequestParam(required = false) Rating.Status status,
                                @RequestParam(required = false) Boolean reported,
                                @RequestParam(required = false) String driverId,
                                @RequestParam(defaultValue = "50") int limit) {
        return ratings.list(status, reported, driverId, limit);
    }

    @GetMapping("/ratings/stats")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public Map<String, Object> ratingStats() {
        return ratings.platformStats();
    }

    @PostMapping("/ratings/{ratingId}/moderation")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public Rating moderate(@AuthenticationPrincipal AuthUser staff, @PathVariable String ratingId,
                           @Valid @RequestBody ModerationRequest body) {
        return ratings.moderate(staff, ratingId, body.action(), body.reason());
    }

    // ------------------------------------------------------------ suporte

    @GetMapping("/support/tickets")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public List<SupportTicket> tickets(@RequestParam(required = false) SupportTicket.Status status,
                                       @RequestParam(required = false) SupportTicket.Category category,
                                       @RequestParam(defaultValue = "50") int limit) {
        return support.list(status, category, limit);
    }

    @GetMapping("/support/tickets/{id}")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public SupportTicket ticket(@AuthenticationPrincipal AuthUser staff, @PathVariable String id) {
        return support.get(staff, id);
    }

    @GetMapping("/support/tickets/{id}/attachments/{index}")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public org.springframework.http.ResponseEntity<byte[]> ticketAttachment(@AuthenticationPrincipal AuthUser staff,
                                                                            @PathVariable String id,
                                                                            @PathVariable int index) {
        SupportService.Attachment a = support.attachment(staff, id, index);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(a.contentType()))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + a.fileName() + "\"")
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox")
                .body(a.content());
    }

    @GetMapping("/support/tickets/{id}/messages")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public List<TicketMessage> ticketMessages(@AuthenticationPrincipal AuthUser staff, @PathVariable String id) {
        return support.messages(staff, id);
    }

    @PostMapping("/support/tickets/{id}")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public SupportTicket updateTicket(@AuthenticationPrincipal AuthUser staff, @PathVariable String id,
                                      @Valid @RequestBody TicketUpdate body) {
        return support.update(staff, id, body.status(), body.priority(), body.assigneeId(), body.note());
    }

    @PostMapping("/support/tickets/{id}/messages")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public TicketMessage replyTicket(@AuthenticationPrincipal AuthUser staff, @PathVariable String id,
                                     @Valid @RequestBody ReplyRequest body) {
        return support.reply(staff, id, body.text());
    }

    // ------------------------------------------------------------ auditoria

    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public PageResponse<AuditLog> audit(@RequestParam(required = false) String targetType,
                                        @RequestParam(required = false) String targetId,
                                        @RequestParam(required = false) String actorId,
                                        @RequestParam(required = false) Long cursor,
                                        @RequestParam(defaultValue = "50") int limit) {
        return audit.list(targetType, targetId, actorId, cursor, Math.min(limit, 200));
    }

    @GetMapping("/security-events")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public List<SecurityEvent> securityEvents(@RequestParam(required = false) String uid,
                                              @RequestParam(defaultValue = "100") int limit) {
        Query q = Query.of(Collections.SECURITY_EVENTS);
        if (uid != null) {
            q.eq("uid", uid);
        }
        return store.query(q.orderBy("at", Query.Direction.DESC).limit(Math.min(limit, 500)), SecurityEvent.class);
    }
}
