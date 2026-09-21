package uz.orientadvertise.services.api.audit;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import uz.orientadvertise.services.domain.audit.AuditEntry;
import uz.orientadvertise.services.domain.audit.AuditRecorder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** AUTH-06: what AuditFilter hands to the recorder — the only writer of {@code audit_log}. */
class AuditFilterTest {

    private AuditRecorder recorder;
    private AuditFilter filter;

    @BeforeEach
    void setUp() {
        recorder = mock(AuditRecorder.class);
        filter = new AuditFilter(recorder);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest post(String uri, String json) {
        var req = new MockHttpServletRequest("POST", uri);
        req.setContentType("application/json");
        if (json != null) {
            req.setContent(json.getBytes(StandardCharsets.UTF_8));
        }
        return req;
    }

    /** A controller stand-in: reads the whole body (as Jackson would) and writes raw UTF-8 bytes. */
    private static FilterChain controller(String responseJson) {
        return (request, response) -> {
            request.getInputStream().readAllBytes();
            response.setContentType("application/json");
            response.getOutputStream().write(responseJson.getBytes(StandardCharsets.UTF_8));
        };
    }

    private AuditEntry run(MockHttpServletRequest req, FilterChain chain, MockHttpServletResponse res) throws Exception {
        filter.doFilter(req, res, chain);
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(recorder).record(entry.capture());
        return entry.getValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/me/password", "/api/auth/reset-password", "/api/auth/refresh", "/api/admin/api-keys"})
    void credentialEndpoints_keepTheEntryButNotTheBodies(String path) throws Exception {
        var entry = run(post(path, "{\"newPassword\":\"hunter2-long\"}"), controller("{\"rawKey\":\"ak_live\"}"),
                new MockHttpServletResponse());

        assertEquals("POST", entry.method());
        assertEquals(path, entry.path());
        assertEquals(AuditFilter.OMITTED_CREDENTIALS, entry.requestBody());
        assertEquals(AuditFilter.OMITTED_CREDENTIALS, entry.responseBody());
    }

    @Test
    void anEncodedPath_isMatchedAfterDecoding() throws Exception {
        var entry = run(post("/api/%61uth/refresh", "{}"), controller("{\"accessToken\":\"jwt\"}"),
                new MockHttpServletResponse());

        assertEquals(AuditFilter.OMITTED_CREDENTIALS, entry.responseBody());
    }

    @Test
    void login_keepsTheUsernameTrail_butMasksThePassword() throws Exception {
        var entry = run(post("/api/auth/login", "{\"username\":\"alice\",\"password\":\"hunter2-long\"}"),
                controller("{\"accessToken\":\"eyJ.jwt\"}"), new MockHttpServletResponse());

        assertTrue(entry.requestBody().contains("\"username\":\"alice\""));
        assertFalse(entry.requestBody().contains("hunter2-long"));
        assertFalse(entry.responseBody().contains("eyJ.jwt"));
    }

    @Test
    void utf8Bodies_areDecodedAsUtf8() throws Exception {
        // Decoding by the wrappers' reported charset (ISO-8859-1) would garble Cyrillic; bodies are UTF-8.
        var entry = run(post("/api/devices/1", "{\"name\":\"Экран\"}"), controller("{\"name\":\"Экран\"}"),
                new MockHttpServletResponse());

        assertEquals("{\"name\":\"Экран\"}", entry.requestBody());
        assertEquals("{\"name\":\"Экран\"}", entry.responseBody());
    }

    @Test
    void aBodyTheControllerNeverRead_isNull() throws Exception {
        FilterChain rejectsEarly = (request, response) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(404);

        var entry = run(post("/api/devices/1", "{\"name\":\"x\"}"), rejectsEarly, new MockHttpServletResponse());

        assertNull(entry.requestBody());
    }

    @Test
    void anOversizedRequestBody_isOmitted() throws Exception {
        var big = "{\"note\":\"" + "x".repeat(AuditFilter.MAX_CACHED_BYTES) + "\"}";

        var entry = run(post("/api/devices/1", big), controller("{}"), new MockHttpServletResponse());

        assertTrue(entry.requestBody().startsWith("[omitted: body larger than"));
    }

    @Test
    void anOversizedResponseBody_isNotParsed_butStillReachesTheClient() throws Exception {
        var big = "{\"note\":\"" + "y".repeat(AuditFilter.MAX_CACHED_BYTES) + "\"}";
        var res = new MockHttpServletResponse();

        var entry = run(post("/api/devices/1", "{}"), controller(big), res);

        assertTrue(entry.responseBody().startsWith("[omitted: "));
        assertEquals(big.length(), res.getContentAsByteArray().length);
    }

    // ---------- DATA-01: device-agent traffic is not audited at all (v1.0.143) ----------

    /**
     * The five calls the device agent makes on a timer. Each one wrote a full request+response row
     * every time — 98.6% of {@code audit_log} on the test server — into a table nothing reads.
     * The filter must return BEFORE it wraps anything, so the response still reaches the client
     * untouched.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/devices/1/heartbeat",
            "/api/devices/1/sync/confirm",
            "/api/devices/1/actions/77/confirm",
            "/api/devices/1/playback",
            "/api/devices/1/remote/abc123/ack"})
    void deviceAgentEndpoints_writeNoAuditRow_butStillAnswerTheClient(String path) throws Exception {
        var res = new MockHttpServletResponse();

        filter.doFilter(post(path, "{\"status\":\"ONLINE\"}"), res, controller("{\"ok\":true}"));

        verify(recorder, never()).record(any());
        assertEquals("{\"ok\":true}", res.getContentAsString());
    }

    /** A percent-encoded agent path is skipped too — the match is on the DECODED path. */
    @Test
    void anEncodedDeviceAgentPath_isSkippedAfterDecoding() throws Exception {
        filter.doFilter(post("/api/devices/1/%68eartbeat", "{}"), new MockHttpServletResponse(),
                controller("{}"));

        verify(recorder, never()).record(any());
    }

    /**
     * The skip must not swallow FAILURES. Device A presenting its token against device B's
     * heartbeat is a {@code @PreAuthorize} 403 raised during the controller invocation, so it
     * unwinds back through this filter — and without a row here it would leave no trail at all.
     * The entry is bodyless: nothing was buffered, which is the point of the skip.
     */
    @ParameterizedTest
    @CsvSource({"403", "404", "422", "500"})
    void aFailedDeviceAgentCall_recordsABodylessEntry(int status) throws Exception {
        FilterChain fails = (request, response) -> {
            ((jakarta.servlet.http.HttpServletResponse) response).setStatus(status);
            response.getOutputStream().write("{\"error\":\"denied\"}".getBytes(StandardCharsets.UTF_8));
        };
        var res = new MockHttpServletResponse();

        var entry = run(post("/api/devices/2/heartbeat", "{\"status\":\"ONLINE\"}"), fails, res);

        assertEquals("POST", entry.method());
        assertEquals("/api/devices/2/heartbeat", entry.path());
        assertEquals(status, entry.responseStatus());
        assertEquals(AuditFilter.OMITTED_AGENT_BODIES, entry.requestBody());
        assertEquals(AuditFilter.OMITTED_AGENT_BODIES, entry.responseBody());
        // The client's response is untouched — this path never wraps it.
        assertEquals("{\"error\":\"denied\"}", res.getContentAsString());
    }

    @Test
    void aSuccessfulDeviceAgentCall_recordsNothing_andTheResponseIsUntouched() throws Exception {
        var res = new MockHttpServletResponse();

        filter.doFilter(post("/api/devices/2/heartbeat", "{}"), res, controller("{\"ok\":true}"));

        assertEquals(200, res.getStatus());
        verify(recorder, never()).record(any());
        assertEquals("{\"ok\":true}", res.getContentAsString());
    }

    /**
     * Everything else under {@code /api/devices/**} is an operator/admin write and MUST stay
     * audited: register, edit, issue an action, start a remote session, stop one.
     */
    @ParameterizedTest
    @CsvSource({
            "POST,/api/devices/1",
            "POST,/api/devices/register",
            "POST,/api/devices/1/actions",
            "POST,/api/devices/1/remote",
            "DELETE,/api/devices/1/remote/abc",
            "POST,/api/devices/1/playlist/control",
            "POST,/api/devices/1/reregistration-window",
            "PUT,/api/devices/1/volume",
            "PUT,/api/devices/volume",
            "DELETE,/api/devices/1"})
    void operatorDeviceWrites_areStillAudited(String method, String path) throws Exception {
        var req = new MockHttpServletRequest(method, path);
        req.setContentType("application/json");
        req.setContent("{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8));

        var entry = run(req, controller("{\"id\":1}"), new MockHttpServletResponse());

        assertEquals(method, entry.method());
        assertEquals(path, entry.path());
    }

    /**
     * The skip list is Ant-matched, so {@code *} spans exactly one segment: a path that merely
     * looks like an agent call is a different endpoint and is audited like any other write.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api/devices/1/heartbeats", "/api/devices/1/playback/extra"})
    void aLookAlikePath_isStillAudited(String path) throws Exception {
        var entry = run(post(path, "{}"), controller("{}"), new MockHttpServletResponse());

        assertEquals(path, entry.path());
    }

    @Test
    void theClientStillGetsItsResponse_whenAuditingFailsWithAnError() throws Exception {
        doThrow(new StackOverflowError("boom")).when(recorder).record(any());
        var res = new MockHttpServletResponse();

        assertThrows(StackOverflowError.class,
                () -> filter.doFilter(post("/api/devices/1", "{}"), res, controller("{\"id\":1}")));

        assertArrayEquals("{\"id\":1}".getBytes(StandardCharsets.UTF_8), res.getContentAsByteArray());
    }
}
