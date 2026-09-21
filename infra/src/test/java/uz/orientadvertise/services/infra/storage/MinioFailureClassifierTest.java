package uz.orientadvertise.services.infra.storage;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.security.InvalidKeyException;
import java.util.concurrent.CompletionException;

import io.minio.errors.ErrorResponseException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import io.minio.messages.ErrorResponse;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Is MinIO broken, or was this request wrong?" — the single decision behind
 * {@link MinioHealthStatus}.
 *
 * <p>Every case here was checked against minio-java 8.5.14's actual behaviour rather than its
 * exception names: a 5xx with an S3 body arrives as {@link ErrorResponseException}, not
 * {@link ServerException}, and {@code S3Base.throwEncapsulatedException} wraps anything outside
 * its nine declared types in a bare {@code RuntimeException}.
 */
class MinioFailureClassifierTest {

    /** A real okhttp Response — the status is the thing being classified, so don't mock it away. */
    private static Response httpResponse(int code) {
        return new Response.Builder()
                .request(new Request.Builder().url("http://minio:9000/content-raw/raw/x.mp4").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("status " + code)
                .build();
    }

    private static ErrorResponseException s3Error(String code, Response response) {
        ErrorResponse err = mock(ErrorResponse.class);
        when(err.code()).thenReturn(code);
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(err);
        when(ex.response()).thenReturn(response);
        return ex;
    }

    // ---------- connection-level: MinIO is broken ----------

    @Test
    void ioException_isConnectionLevel() {
        assertTrue(MinioFailureClassifier.isConnectionLevel(new ConnectException("refused")));
        assertTrue(MinioFailureClassifier.isConnectionLevel(new SocketTimeoutException("read timed out")));
        assertTrue(MinioFailureClassifier.isConnectionLevel(new java.net.UnknownHostException("minio")));
        assertTrue(MinioFailureClassifier.isConnectionLevel(new IOException("unexpected end of stream")));
    }

    @Test
    void ioExceptionBuriedInTheCauseChain_isConnectionLevel() {
        // S3Base.throwEncapsulatedException rethrows nine declared types and wraps EVERYTHING else
        // in `new RuntimeException(cause)`. Classifying on the thrown type alone loses this outage.
        var wrapped = new RuntimeException(new ConnectException("Connection refused"));
        assertTrue(MinioFailureClassifier.isConnectionLevel(wrapped));

        var twiceWrapped = new IllegalStateException("io failure",
                new CompletionException(new SocketTimeoutException("timeout")));
        assertTrue(MinioFailureClassifier.isConnectionLevel(twiceWrapped));
    }

    @Test
    void serverException_isConnectionLevel() {
        assertTrue(MinioFailureClassifier.isConnectionLevel(
                new ServerException("Internal Server Error", 500, null)));
    }

    @Test
    void invalidResponseException_isConnectionLevel() {
        // The bytes were not an S3 response at all — a proxy error page, a captive portal, a
        // half-started MinIO. That is the endpoint being broken, not the request.
        assertTrue(MinioFailureClassifier.isConnectionLevel(
                new InvalidResponseException(200, "text/html", "<html>502 Bad Gateway</html>", null)));
    }

    @Test
    void errorResponseExceptionWith5xxStatus_isConnectionLevel() {
        // A 5xx that DOES carry an S3 XML body never becomes a ServerException — this is the shape
        // "MinIO is up but broken" actually arrives in.
        assertTrue(MinioFailureClassifier.isConnectionLevel(s3Error("InternalError", httpResponse(500))));
        assertTrue(MinioFailureClassifier.isConnectionLevel(s3Error("SlowDown", httpResponse(503))));
    }

    @Test
    void errorResponseExceptionWith5xxCodeButNoResponse_isConnectionLevel() {
        // The SDK does not always carry the okhttp Response through; fall back to the S3 code.
        assertTrue(MinioFailureClassifier.isConnectionLevel(s3Error("InternalError", null)));
        assertTrue(MinioFailureClassifier.isConnectionLevel(s3Error("ServiceUnavailable", null)));
    }

    // ---------- per-request: MinIO answered about one object ----------

    @Test
    void errorResponseExceptionWith4xxStatus_isPerRequest() {
        assertFalse(MinioFailureClassifier.isConnectionLevel(s3Error("NoSuchKey", httpResponse(404))));
        assertFalse(MinioFailureClassifier.isConnectionLevel(s3Error("NoSuchBucket", httpResponse(404))));
        assertFalse(MinioFailureClassifier.isConnectionLevel(s3Error("AccessDenied", httpResponse(403))));
        // A full bucket is a healthy MinIO saying no — and MinIO answers it with HTTP 507, so the
        // CODE has to win over the status. A status-only rule would degrade here, then the probe
        // (a read, which a full MinIO still serves) would heal it on the next tick: a degrade
        // /recover pair, with its WARN and INFO in the operator chat, per upload attempt.
        assertFalse(MinioFailureClassifier.isConnectionLevel(s3Error("XMinioStorageFull", httpResponse(507))));
    }

    @Test
    void quotaFullWithNoResponse_isStillPerRequest() {
        // 507 is not in the 5xx fallback code list on purpose — XMinioStorageFull is about storage
        // capacity, not about MinIO being unable to serve.
        assertFalse(MinioFailureClassifier.isConnectionLevel(s3Error("XMinioStorageFull", null)));
    }

    @Test
    void argumentAndParsingFamilies_arePerRequest() {
        assertFalse(MinioFailureClassifier.isConnectionLevel(new IllegalArgumentException("bucket name empty")));
        assertFalse(MinioFailureClassifier.isConnectionLevel(new InvalidKeyException("bad key")));
        assertFalse(MinioFailureClassifier.isConnectionLevel(new XmlParserException(new Exception("boom"))));
        assertFalse(MinioFailureClassifier.isConnectionLevel(new RuntimeException("something odd")));
    }

    @Test
    void errorResponseExceptionWithNoParsedBody_fallsBackToTheHttpStatus() {
        // Set.of(...).contains(null) throws NPE, and "no parsed body" is precisely the case where
        // the classifier must answer rather than blow up inside a catch block.
        ErrorResponseException noBody = mock(ErrorResponseException.class);
        when(noBody.errorResponse()).thenReturn(null);
        when(noBody.response()).thenReturn(httpResponse(502));
        assertTrue(MinioFailureClassifier.isConnectionLevel(noBody));

        ErrorResponseException noBodyNoResponse = mock(ErrorResponseException.class);
        when(noBodyNoResponse.errorResponse()).thenReturn(null);
        when(noBodyNoResponse.response()).thenReturn(null);
        assertFalse(MinioFailureClassifier.isConnectionLevel(noBodyNoResponse));
    }

    @Test
    void nullAndSelfReferentialChains_areHandled() {
        assertFalse(MinioFailureClassifier.isConnectionLevel(null));

        // A cycle must terminate rather than spin the caller's thread.
        var loop = new RuntimeException("a");
        loop.initCause(new RuntimeException("b") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        });
        assertFalse(MinioFailureClassifier.isConnectionLevel(loop));
    }
}
