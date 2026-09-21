package uz.orientadvertise.services.domain.content;

/**
 * Server → device WebSocket push message types.
 * The wire format is plain JSON: {"type":"SYNC_REQUIRED", ...}
 *
 * <p><b>Emit the {@code type} field from {@link #name()}, never from a string literal.</b>
 * {@code BatchedSyncDispatcher} emits a hard-coded {@code "SYNC_CONTENT"} that is not a member
 * of this enum — a drift that makes the enum decorative for that one message. Every new emitter
 * must format from the constant so the wire and the enum can never diverge again.
 */
public enum PushMessageType {
    /** Device's content version doesn't match expected — refetch the playlist. */
    SYNC_REQUIRED,
    /** A {@code remote_action} is queued (REBOOT, UPDATE_CONTENT, etc.). */
    ACTION_PENDING,
    /** New urgent content has been uploaded — fetch immediately, don't wait. */
    URGENT_CONTENT,
    /** A remote view/control session has been opened; dial the relay. */
    REMOTE_SESSION_START,
    /** Tear down the named remote session now. */
    REMOTE_SESSION_STOP
}
