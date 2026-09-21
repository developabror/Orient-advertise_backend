package uz.orientadvertise.services.service.exception;

/**
 * Remote control is switched off for this deployment ({@code app.remote.enabled=false}).
 * Mapped to <b>503 Service Unavailable</b> — the capability exists in the build but is not
 * currently offered, which is a server-side condition the caller cannot fix by changing the
 * request.
 *
 * <p>The feature ships dark by design: no relay, no secret, no exposure until an environment
 * opts in.
 */
public class RemoteControlDisabledException extends RuntimeException {

    public RemoteControlDisabledException(String message) {
        super(message);
    }
}
