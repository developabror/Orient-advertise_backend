package uz.orientadvertise.services.common.exception;

/**
 * Service-layer authorization failure. Mapped to HTTP 403 by the global handler so
 * service code can reject a request without depending on Spring Security types
 * (which aren't on the service module's classpath).
 */
public class AccessForbiddenException extends RuntimeException {

    public AccessForbiddenException(String message) {
        super(message);
    }
}
