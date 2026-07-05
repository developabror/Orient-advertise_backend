package uz.orientadvertise.services.infra.auth;

import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.repository.AppUserRepository;

@Component
public class JpaUserActiveChecker implements UserActiveChecker {

    private final AppUserRepository userRepository;

    public JpaUserActiveChecker(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean isActive(String username) {
        return userRepository.existsByUsernameAndIsActiveTrue(username);
    }
}
