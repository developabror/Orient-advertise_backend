package uz.orientadvertise.services.infra.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.auth.UserInfo;

@Repository
public class InMemoryUserRepository {

    private final Map<String, UserInfo> users = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        // Default users for development — replace with JPA in production
        users.put("admin", new UserInfo("admin", "password", Role.ADMIN));
        users.put("operator", new UserInfo("operator", "password", Role.OPERATOR));
        users.put("viewer", new UserInfo("viewer", "password", Role.VIEWER));
        users.put("advertiser", new UserInfo("advertiser", "password", Role.ADVERTISER));
        users.put("norole", new UserInfo("norole", "password", null));
    }

    public Optional<UserInfo> findByUsername(String username) {
        return Optional.ofNullable(users.get(username));
    }

    public void updateRole(String username, Role role) {
        users.computeIfPresent(username, (k, existing) ->
                new UserInfo(existing.username(), existing.passwordHash(), role));
    }
}
