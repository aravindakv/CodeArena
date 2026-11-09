package dev.codearena.auth;

import dev.codearena.common.security.Roles;
import dev.codearena.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Only an ADMIN can grant or remove ADMIN. Every change is written to the AuditLog table. */
@RestController
@RequestMapping("/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
class AdminUserController {
    private final UserRepository users;

    AdminUserController(UserRepository users) {
        this.users = users;
    }

    record UserRow(String id, String email, String displayName, Set<String> roles, String status) {}

    record RoleChange(boolean admin) {}

    @GetMapping
    List<UserRow> list() {
        return users.list(200).stream()
                .map(u -> new UserRow(u.id(), u.email(), u.displayName(), u.roles(), u.status())).toList();
    }

    @PostMapping("/{id}/admin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setAdmin(@PathVariable String id, @RequestBody RoleChange change, @AuthenticationPrincipal Jwt actor) {
        if (id.equals(actor.getSubject()) && !change.admin())
            throw ApiException.badRequest("SELF_DEMOTION", "you cannot remove your own admin role");
        var u = users.byId(id).orElseThrow(() -> ApiException.notFound("user"));
        Set<String> roles = new HashSet<>(u.roles());
        if (change.admin()) roles.add(Roles.ADMIN); else roles.remove(Roles.ADMIN);
        users.setRoles(id, roles);
        users.audit(actor.getSubject(), change.admin() ? "GRANT_ADMIN" : "REVOKE_ADMIN", id);
    }
}