package uz.orientadvertise.services.domain.auth;

public record UserInfo(String username, String passwordHash, Role role) {

    public boolean hasRole() {
        return role != null;
    }
}
