package uz.orientadvertise.services.api.ws;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.content.DevicePushChannel;
import uz.orientadvertise.services.service.RemoteActionIssuedEvent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteActionPushListenerTest {

    private final DevicePushChannel pushChannel = mock(DevicePushChannel.class);
    private final RemoteActionPushListener listener = new RemoteActionPushListener(pushChannel);
    private final Instant issuedAt = Instant.parse("2026-09-22T10:00:00Z");

    @Test
    void pushesTheSameActionPendingFrameTheConnectReplaySends() {
        listener.onRemoteActionIssued(new RemoteActionIssuedEvent(41L, 7L, "REBOOT", issuedAt));

        verify(pushChannel).push(7L,
                "{\"type\":\"ACTION_PENDING\",\"actionId\":41,\"actionType\":\"REBOOT\",\"issuedAt\":\"2026-09-22T10:00:00Z\"}");
    }

    @Test
    void offlineDevice_isNotAnError_theHeartbeatDeliversIt() {
        when(pushChannel.push(anyLong(), anyString())).thenReturn(false);

        assertDoesNotThrow(() -> listener.onRemoteActionIssued(new RemoteActionIssuedEvent(41L, 7L, "REBOOT", issuedAt)));
    }

    @Test
    void aFailingPush_neverReachesTheCaller() {
        // A concurrent send on the same socket throws IllegalStateException, not IOException.
        when(pushChannel.push(anyLong(), anyString())).thenThrow(new IllegalStateException("TEXT_PARTIAL_WRITING"));

        assertDoesNotThrow(() -> listener.onRemoteActionIssued(new RemoteActionIssuedEvent(41L, 7L, "REBOOT", issuedAt)));
    }
}
