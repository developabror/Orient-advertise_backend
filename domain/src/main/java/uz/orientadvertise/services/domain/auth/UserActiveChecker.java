package uz.orientadvertise.services.domain.auth;

public interface UserActiveChecker {

    boolean isActive(String username);
}
