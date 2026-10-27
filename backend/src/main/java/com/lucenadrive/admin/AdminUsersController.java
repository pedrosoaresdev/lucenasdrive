package com.lucenadrive.admin;

import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.Role;
import com.lucenadrive.user.PrivacyRequest;
import com.lucenadrive.user.User;
import com.lucenadrive.user.UserStatus;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin · Usuários, equipe e privacidade")
public class AdminUsersController {

    private final AdminUserService users;

    public AdminUsersController(AdminUserService users) {
        this.users = users;
    }

    public record StatusRequest(@NotNull UserStatus status, @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record ReasonRequest(@NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record StaffRoleRequest(@NotBlank @Email String email, @NotNull Role role, boolean grant,
                                   @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record PrivacyUpdate(@NotNull PrivacyRequest.Status status, @Size(max = 1000) String note) {
    }

    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public List<User> list(@RequestParam(required = false) Role role, @RequestParam(required = false) UserStatus status,
                           @RequestParam(required = false) String email, @RequestParam(defaultValue = "50") int limit) {
        return users.list(role, status, email, limit);
    }

    @GetMapping("/users/{uid}")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public AdminUserService.UserDetail detail(@PathVariable String uid) {
        return users.detail(uid);
    }

    @PostMapping("/users/{uid}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public User setStatus(@AuthenticationPrincipal AuthUser staff, @PathVariable String uid,
                          @Valid @RequestBody StatusRequest body) {
        return users.setStatus(staff, uid, body.status(), body.reason());
    }

    @PostMapping("/users/{uid}/pii")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public Map<String, String> pii(@AuthenticationPrincipal AuthUser staff, @PathVariable String uid,
                                   @Valid @RequestBody ReasonRequest body) {
        return users.revealPii(staff, uid, body.reason());
    }

    @GetMapping("/staff")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public List<User> staff() {
        return users.staff();
    }

    @PostMapping("/staff/roles")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public User changeRole(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody StaffRoleRequest body) {
        return users.changeStaffRole(actor, body.email(), body.role(), body.grant(), body.reason());
    }

    @GetMapping("/privacy-requests")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public List<PrivacyRequest> privacy(@RequestParam(required = false) PrivacyRequest.Status status) {
        return users.privacyRequests(status);
    }

    @PostMapping("/privacy-requests/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public PrivacyRequest updatePrivacy(@AuthenticationPrincipal AuthUser staff, @PathVariable String id,
                                        @Valid @RequestBody PrivacyUpdate body) {
        return users.updatePrivacyRequest(staff, id, body.status(), body.note());
    }
}
