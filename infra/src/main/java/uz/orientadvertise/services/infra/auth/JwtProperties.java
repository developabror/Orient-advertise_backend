package uz.orientadvertise.services.infra.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    // No default — a blank/short secret must fail startup (validated in JwtTokenProvider),
    // not silently fall back to a well-known development key.
    private String secret;
    private int accessTokenExpiryMinutes = 15;
    private int refreshTokenExpiryDays = 7;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public int getAccessTokenExpiryMinutes() {
        return accessTokenExpiryMinutes;
    }

    public void setAccessTokenExpiryMinutes(int accessTokenExpiryMinutes) {
        this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
    }

    public int getRefreshTokenExpiryDays() {
        return refreshTokenExpiryDays;
    }

    public void setRefreshTokenExpiryDays(int refreshTokenExpiryDays) {
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
    }
}
