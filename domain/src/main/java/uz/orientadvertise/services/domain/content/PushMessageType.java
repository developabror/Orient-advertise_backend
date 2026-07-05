package uz.orientadvertise.services.domain.content;

/**
 * Server → device WebSocket push message types.
 * The wire format is plain JSON: {"type":"SYNC_REQUIRED", ...}
 */
public enum PushMessageType {
    /** Device's content version doesn't match expected — refetch the playlist. */
    SYNC_REQUIRED,
    /** A {@code remote_action} is queued (REBOOT, UPDATE_CONTENT, etc.). */
    ACTION_PENDING,
    /** New urgent content has been uploaded — fetch immediately, don't wait. */
    URGENT_CONTENT
}
