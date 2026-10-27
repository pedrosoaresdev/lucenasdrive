package com.lucenadrive.admin;

import com.lucenadrive.chat.ChatMessage;
import com.lucenadrive.chat.ChatService;
import com.lucenadrive.common.web.PageResponse;
import com.lucenadrive.ledger.LedgerEntry;
import com.lucenadrive.ride.RideStatus;
import com.lucenadrive.ride.RideView;
import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/rides")
@Tag(name = "Admin · Corridas")
public class AdminRidesController {

    private final AdminRideService admin;
    private final ChatService chat;

    public AdminRidesController(AdminRideService admin, ChatService chat) {
        this.admin = admin;
        this.chat = chat;
    }

    public record CancelRequest(@NotBlank @Size(min = 10, max = 300) String reason) {
    }

    public record AdjustmentRequest(@NotBlank @Pattern(regexp = "PLATFORM|DRIVER") String account,
                                    @NotNull LedgerEntry.Direction direction, @Min(1) long amountCents,
                                    @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    @GetMapping
    public PageResponse<RideView> list(@RequestParam(required = false) RideStatus status,
                                       @RequestParam(required = false) String cityId,
                                       @RequestParam(required = false) String driverId,
                                       @RequestParam(required = false) String passengerId,
                                       @RequestParam(required = false) Instant from,
                                       @RequestParam(required = false) Instant to,
                                       @RequestParam(required = false) Long cursor,
                                       @RequestParam(defaultValue = "25") int limit) {
        return admin.list(new AdminRideService.RideFilter(status, cityId, driverId, passengerId, from, to), cursor, limit);
    }

    @GetMapping("/live")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public List<AdminRideService.LiveRide> live(@RequestParam(required = false) String cityId) {
        return admin.live(cityId);
    }

    @GetMapping("/{rideId}")
    public AdminRideService.RideDetail detail(@PathVariable String rideId) {
        return admin.detail(rideId);
    }

    @GetMapping("/{rideId}/chat")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public PageResponse<ChatMessage> chat(@AuthenticationPrincipal AuthUser staff, @PathVariable String rideId,
                                          @RequestParam String reason, @RequestParam(required = false) Long before,
                                          @RequestParam(defaultValue = "50") int limit) {
        return chat.adminHistory(staff, rideId, reason, before, limit);
    }

    @PostMapping("/{rideId}/cancel")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public RideView cancel(@AuthenticationPrincipal AuthUser staff, @PathVariable String rideId,
                           @Valid @RequestBody CancelRequest body) {
        return admin.cancel(staff, rideId, body.reason());
    }

    @PostMapping("/{rideId}/adjustments")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OWNER')")
    public LedgerEntry adjust(@AuthenticationPrincipal AuthUser staff, @PathVariable String rideId,
                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                              @Valid @RequestBody AdjustmentRequest body) {
        return admin.adjust(staff, rideId, body.account(), body.direction(), body.amountCents(), body.reason(),
                idempotencyKey);
    }
}
