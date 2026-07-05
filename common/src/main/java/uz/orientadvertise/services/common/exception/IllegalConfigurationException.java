package uz.orientadvertise.services.common.exception;

/**
 * Signals a server-side configuration or provisioning fault — a mandatory JDK algorithm
 * missing, or a misconfigured/absent default region — NOT an operator-correctable conflict.
 *
 * <p>Deliberately extends {@link RuntimeException} (not {@link IllegalStateException}) so the
 * {@code GlobalExceptionHandler} catch-all maps it to HTTP 500, never a 409. A 409 would tell
 * the operator "fix your request" when the real fault is server config the operator cannot
 * influence; routing through the 500 path also forwards it to the ops alert channel.
 */
public class IllegalConfigurationException extends RuntimeException {

    public IllegalConfigurationException(String message) {
        super(message);
    }

    public IllegalConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
