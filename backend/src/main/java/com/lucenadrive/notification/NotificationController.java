package com.lucenadrive.notification;

import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A listagem é feita pelo cliente via listener do Firestore (somente as próprias notificações). */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notificações")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        service.markRead(user.uid(), id);
    }
}
