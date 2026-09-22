package uz.orientadvertise.services.api;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.orientadvertise.services.Application;
import uz.orientadvertise.services.api.ws.DeviceWebSocketHandler;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.service.BulkRemoteActionService;
import uz.orientadvertise.services.service.RemoteActionService;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * VG-04 on the real wiring: an issued action reaches the device's socket once its transaction
 * commits — and never when it rolls back. The group path saves each action in its own short
 * transaction with no transaction around the loop, which only the listener's fallbackExecution
 * covers.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class RemoteActionPushIntegrationTest {

    private static final long PROJECT = 9150L;
    private static final long GROUP = 9150L;
    private static final long DEVICE_A = 915001L;
    private static final long DEVICE_B = 915002L;

    @MockitoSpyBean private DeviceWebSocketHandler deviceSockets;

    @Autowired private RemoteActionService remoteActionService;
    @Autowired private BulkRemoteActionService bulkRemoteActionService;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager txManager;

    @BeforeEach
    void seed() {
        seedOnce("project", PROJECT, "INSERT INTO project (id, name, created_at, updated_at) "
                + "VALUES (9150, 'ActionPushProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        seedOnce("region", PROJECT, "INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                + "VALUES (9150, 9150, 'ActionPushRegion', 'APR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        seedOnce("device_group", GROUP, "INSERT INTO device_group (id, project_id, name, created_at, updated_at) "
                + "VALUES (9150, 9150, 'ActionPushGroup', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        for (long id : List.of(DEVICE_A, DEVICE_B)) {
            seedOnce("device", id, "INSERT INTO device (id, region_id, device_group_id, serial_number, name, status, "
                    + "device_token, registered_at, created_at, updated_at) VALUES (" + id + ", 9150, 9150, "
                    + "'SN-APUSH-" + id + "', 'Action push " + id + "', 'ONLINE', 'tok-apush-" + id + "', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            jdbcTemplate.update("DELETE FROM remote_action WHERE device_id = ?", id);
        }
        clearInvocations(deviceSockets);
    }

    private void seedOnce(String table, long id, String insert) {
        Integer present = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        if (present == null || present == 0) {
            jdbcTemplate.update(insert);
        }
    }

    @Test
    void committedAction_isPushedToTheDevice() {
        var action = new TransactionTemplate(txManager).execute(status -> remoteActionService.issue(
                deviceRepository.findById(DEVICE_A).orElseThrow(), "REBOOT", "{}", "operator"));

        verify(deviceSockets).push(eq(DEVICE_A), contains("\"actionId\":" + action.getId()));
        verify(deviceSockets).push(eq(DEVICE_A), contains("\"type\":\"ACTION_PENDING\""));
    }

    @Test
    void rolledBackAction_isNeverPushed() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            remoteActionService.issue(deviceRepository.findById(DEVICE_A).orElseThrow(), "REBOOT", "{}", "operator");
            status.setRollbackOnly();
        });

        verify(deviceSockets, never()).push(anyLong(), anyString());
    }

    @Test
    void groupAction_isPushedToEveryMember_evenWithNoSurroundingTransaction() {
        var result = bulkRemoteActionService.issueToGroup(GROUP, "SYNC_CONTENT", "{}", "operator");

        verify(deviceSockets).push(eq(DEVICE_A), contains("\"actionType\":\"SYNC_CONTENT\""));
        verify(deviceSockets).push(eq(DEVICE_B), contains("\"actionType\":\"SYNC_CONTENT\""));
        org.junit.jupiter.api.Assertions.assertEquals(2, result.succeededCount());
    }
}
