package com.lucenadrive.chat;

import com.lucenadrive.common.web.PageResponse;
import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rides/{rideId}/chat")
@Tag(name = "Chat da corrida")
public class ChatController {

    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    public record SendMessageRequest(@NotBlank @Size(max = 64) String clientMessageId,
                                     @Size(max = 2000) String text, @Valid LocationBody location) {
    }

    public record LocationBody(double lat, double lng) {
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatMessage send(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId,
                            @Valid @RequestBody SendMessageRequest body) {
        if (body.location() != null) {
            return chat.send(user, rideId, body.clientMessageId(), body.text(), body.location().lat(), body.location().lng());
        }
        return chat.send(user, rideId, body.clientMessageId(), body.text());
    }

    @GetMapping("/messages")
    public PageResponse<ChatMessage> history(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId,
                                             @RequestParam(required = false) Long before,
                                             @RequestParam(defaultValue = "30") int limit) {
        return chat.history(user, rideId, before, limit);
    }

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId) {
        chat.markRead(user, rideId);
    }
}
