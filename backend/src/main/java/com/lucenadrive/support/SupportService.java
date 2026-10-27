package com.lucenadrive.support;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.chat.MessageSanitizer;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.common.web.Ids;
import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.notification.NotificationType;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SupportService {

    private final DocStore store;
    private final NotificationService notifications;
    private final AuditService audit;
    private final com.lucenadrive.integration.ObjectStorage storage;
    private final Clock clock;

    public static final java.util.Set<String> ATTACHMENT_TYPES = java.util.Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/webp");
    public static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

    public record Attachment(byte[] content, String contentType, String fileName) {
    }

    public SupportService(DocStore store, NotificationService notifications, AuditService audit,
                          com.lucenadrive.integration.ObjectStorage storage, Clock clock) {
        this.store = store;
        this.notifications = notifications;
        this.audit = audit;
        this.storage = storage;
        this.clock = clock;
    }

    public SupportTicket open(AuthUser user, SupportTicket.Category category, String rideId, String subject,
                              String description, Map<String, String> metadata) {
        return open(user, category, rideId, subject, description, List.of(), metadata);
    }

    public SupportTicket open(AuthUser user, SupportTicket.Category category, String rideId, String subject,
                              String description, List<String> attachments, Map<String, String> metadata) {
        if (rideId != null) {
            Ride ride = store.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
            if (!ride.isParticipant(user.uid())) {
                throw ApiException.forbidden("Você não participa desta corrida.");
            }
        }
        for (String path : attachments) {
            if (!path.startsWith("supportAttachments/" + user.uid() + "/") || path.contains("..")) {
                throw ApiException.forbidden("Anexo inválido.");
            }
            var info = storage.stat(path).orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Anexo não encontrado. Envie o arquivo novamente."));
            if (!ATTACHMENT_TYPES.contains(info.contentType()) || info.sizeBytes() > MAX_ATTACHMENT_BYTES) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Anexo deve ser PDF ou imagem de até 10 MB.");
            }
        }
        SupportTicket.Priority priority = switch (category) {
            case SAFETY, INAPPROPRIATE_BEHAVIOR -> SupportTicket.Priority.HIGH;
            case PAYMENT, RIDE_ISSUE -> SupportTicket.Priority.NORMAL;
            default -> SupportTicket.Priority.LOW;
        };
        Instant now = Instant.now(clock);
        boolean driverOfRide = rideId != null && store.get(Collections.RIDES, rideId, Ride.class)
                .map(r -> user.uid().equals(r.driverId())).orElse(false);
        String role = driverOfRide || !user.hasRole(Role.PASSENGER) ? "DRIVER" : "PASSENGER";
        SupportTicket t = new SupportTicket(Ids.newId(), user.uid(), role, category, priority, SupportTicket.Status.OPEN,
                rideId, MessageSanitizer.sanitize(subject), MessageSanitizer.sanitize(description), attachments, null,
                metadata == null ? Map.of() : metadata,
                List.of(new SupportTicket.StatusChange(SupportTicket.Status.OPEN, user.uid(), null, now)), now, now);
        store.set(Collections.SUPPORT_TICKETS, t.id(), t);
        return t;
    }

    public List<SupportTicket> mine(AuthUser user) {
        return store.query(Query.of(Collections.SUPPORT_TICKETS).eq("userId", user.uid())
                .orderBy("createdAt", Query.Direction.DESC).limit(50), SupportTicket.class);
    }

    public SupportTicket get(AuthUser user, String id) {
        SupportTicket t = store.get(Collections.SUPPORT_TICKETS, id, SupportTicket.class)
                .orElseThrow(() -> ApiException.notFound("Chamado"));
        if (!t.userId().equals(user.uid()) && !(user.isStaff() && user.mfaVerified())) {
            throw ApiException.forbidden("Chamado de outro usuário.");
        }
        return t;
    }

    public List<TicketMessage> messages(AuthUser user, String ticketId) {
        get(user, ticketId);
        return store.query(Query.of(Collections.ticketMessages(ticketId)).orderBy("createdAt", Query.Direction.ASC)
                .limit(200), TicketMessage.class);
    }

    public TicketMessage reply(AuthUser user, String ticketId, String text) {
        SupportTicket t = get(user, ticketId);
        boolean staff = !t.userId().equals(user.uid());
        if (t.status() == SupportTicket.Status.CLOSED) {
            throw new ApiException(ErrorCode.CONFLICT, "Chamado encerrado.");
        }
        String clean = MessageSanitizer.sanitize(text);
        if (clean.isEmpty() || clean.length() > 2000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mensagem deve ter de 1 a 2000 caracteres.");
        }
        Instant now = Instant.now(clock);
        TicketMessage m = new TicketMessage(Ids.newId(), ticketId, user.uid(), staff, clean, null, now);
        store.set(Collections.ticketMessages(ticketId), m.id(), m);
        if (staff) {
            store.set(Collections.SUPPORT_TICKETS, t.id(),
                    t.with(SupportTicket.Status.WAITING_USER, t.priority(), t.assigneeId() == null ? user.uid() : t.assigneeId(),
                            user.uid(), "Resposta da equipe", now));
            notifications.notify(t.userId(), NotificationType.SUPPORT_UPDATE, "Atualização do seu chamado",
                    "A equipe Lucena Drive respondeu seu chamado.", Map.of("ticketId", t.id()));
        } else if (t.status() == SupportTicket.Status.WAITING_USER) {
            store.set(Collections.SUPPORT_TICKETS, t.id(),
                    t.with(SupportTicket.Status.IN_PROGRESS, t.priority(), t.assigneeId(), user.uid(), "Resposta do usuário", now));
        }
        return m;
    }

    // ---------------------------------------------------------------- equipe

    public List<SupportTicket> list(SupportTicket.Status status, SupportTicket.Category category, int limit) {
        Query q = Query.of(Collections.SUPPORT_TICKETS);
        if (status != null) {
            q.eq("status", status);
        }
        if (category != null) {
            q.eq("category", category);
        }
        return store.query(q.orderBy("createdAt", Query.Direction.DESC).limit(Math.min(limit, 200)), SupportTicket.class);
    }

    public SupportTicket update(AuthUser staff, String id, SupportTicket.Status status, SupportTicket.Priority priority,
                                String assigneeId, String note) {
        return store.transaction(tx -> {
            SupportTicket t = tx.get(Collections.SUPPORT_TICKETS, id, SupportTicket.class)
                    .orElseThrow(() -> ApiException.notFound("Chamado"));
            SupportTicket next = t.with(status != null ? status : t.status(), priority != null ? priority : t.priority(),
                    assigneeId != null ? assigneeId : t.assigneeId(), staff.uid(), note, Instant.now(clock));
            tx.set(Collections.SUPPORT_TICKETS, id, next);
            audit.recordInTx(tx, staff, "SUPPORT_TICKET_UPDATED", "supportTicket", id,
                    Map.of("status", t.status().name(), "priority", t.priority().name()),
                    Map.of("status", next.status().name(), "priority", next.priority().name()), note);
            return next;
        });
    }

    /**
     * Entrega um anexo à equipe pelo próprio backend (sem URL pública), registrando auditoria.
     * Tipos restritos a PDF/imagens, servidos sempre como download/visualização sem execução.
     */
    public Attachment attachment(AuthUser staff, String ticketId, int index) {
        SupportTicket t = store.get(Collections.SUPPORT_TICKETS, ticketId, SupportTicket.class)
                .orElseThrow(() -> ApiException.notFound("Chamado"));
        List<String> paths = t.attachmentPaths() == null ? List.of() : t.attachmentPaths();
        if (index < 0 || index >= paths.size()) {
            throw ApiException.notFound("Anexo");
        }
        String path = paths.get(index);
        var info = storage.stat(path).orElseThrow(() -> ApiException.notFound("Anexo"));
        if (!ATTACHMENT_TYPES.contains(info.contentType())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tipo de anexo não permitido.");
        }
        byte[] content = storage.read(path, MAX_ATTACHMENT_BYTES).orElseThrow(() -> ApiException.notFound("Anexo"));
        audit.record(staff, "SUPPORT_ATTACHMENT_VIEWED", "supportTicket", ticketId, Map.of(),
                Map.of("index", index, "contentType", info.contentType()), "Visualização de anexo de suporte");
        String ext = switch (info.contentType()) {
            case "application/pdf" -> "pdf";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
        return new Attachment(content, info.contentType(), "anexo-" + ticketId + "-" + (index + 1) + "." + ext);
    }
}
