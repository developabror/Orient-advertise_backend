package uz.orientadvertise.services.service.exception;

/**
 * The device has explicitly reported {@code capability.supported == false} — the request is
 * well-formed and the caller is authorised, but this box cannot do remote control. Mapped to
 * <b>422 Unprocessable Entity</b>.
 *
 * <p>Deliberately distinct from a 409: nothing about the current state will change by retrying,
 * and distinct from a 400: the request itself is fine. A {@code null} capability ("never
 * reported") is <b>not</b> this case — an unknown device is allowed to try.
 */
public class RemoteCapabilityUnsupportedException extends RuntimeException {

    public RemoteCapabilityUnsupportedException(String message) {
        super(message);
    }
}
