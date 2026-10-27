package com.lucenadrive.support;

import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/support/tickets")
@Tag(name = "Suporte")
public class SupportController {

    private final SupportService support;

    public SupportController(SupportService support) {
        this.support = support;
    }

    public record OpenTicketRequest(@NotNull SupportTicket.Category category, String rideId,
                                    @NotBlank @Size(max = 120) String subject,
                                    @NotBlank @Size(max = 4000) String description,
                                    @Size(max = 5) List<@Size(max = 500) String> attachmentPaths) {
    }

    public record ReplyRequest(@NotBlank @Size(max = 2000) String text) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupportTicket open(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody OpenTicketRequest body) {
        return support.open(user, body.category(), body.rideId(), body.subject(), body.description(),
                body.attachmentPaths() == null ? List.of() : body.attachmentPaths(), Map.of());
    }

    @GetMapping
    public List<SupportTicket> mine(@AuthenticationPrincipal AuthUser user) {
        return support.mine(user);
    }

    @GetMapping("/{id}")
    public SupportTicket get(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        return support.get(user, id);
    }

    @GetMapping("/{id}/messages")
    public List<TicketMessage> messages(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        return support.messages(user, id);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketMessage reply(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
                               @Valid @RequestBody ReplyRequest body) {
        return support.reply(user, id, body.text());
    }
}
