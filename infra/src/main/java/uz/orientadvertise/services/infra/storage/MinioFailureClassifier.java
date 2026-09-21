package uz.orientadvertise.services.infra.storage;

import java.io.IOException;
import java.util.Set;

import io.minio.errors.ErrorResponseException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;

/**
 * Answers one question for every MinIO failure: is this evidence that <em>MinIO</em> is broken, or
 * evidence that <em>one request</em> was wrong?
 *
 * <p>Shared by {@link MinioStorageClient} (every storage call) and the Telegram {@code /health}
 * probe, because both write to {@link MinioHealthStatus} and a flag with two writers that disagree
 * about what "down" means is worse than no flag. It is the reason v1.0.144 exists: every call site
 * used to answer "MinIO is broken" by catching {@code Exception}, so a deleted key, a denied ACL or
 * a full quota flipped a process-wide flag and 503'd the whole fleet.
 *
 * <p><b>Verified against minio-java 8.5.14, not assumed.</b> The SDK's mapping is narrower and
 * lumpier than the exception names suggest:
 * <ul>
 *   <li>{@link ServerException} is built in exactly one place ({@code S3Base$1}) — a 5xx whose body
 *       could not be parsed as an S3 error. A 5xx that <em>does</em> carry an S3 XML body
 *       ({@code InternalError}, {@code SlowDown}, {@code ServiceUnavailable}) arrives as an
 *       {@link ErrorResponseException} instead, which is why the HTTP status is checked rather than
 *       the exception type.</li>
 *   <li>{@code S3Base.throwEncapsulatedException} rethrows nine listed types unchanged and wraps
 *       <em>everything else</em> in {@code new RuntimeException(cause)}. Classifying on the thrown
 *       type alone therefore loses real outages, so the cause chain is walked for an
 *       {@link IOException}.</li>
 *   <li>{@link InvalidResponseException} means the bytes on the wire were not an S3 response at
 *       all — a captive portal, a proxy error page, a half-deployed MinIO. That is the endpoint
 *       being broken, not the request.</li>
 * </ul>
 *
 * <p><b>Why the bias changed.</b> The first cut treated anything unrecognised as per-request,
 * reasoning that over-degrading costs the whole fleet. That asymmetry held only while a degraded
 * flag was a <em>latch</em> that needed a restart. With {@link MinioHealthProbe} clearing it within
 * one {@code app.minio.recheck-interval}, the costs are now ~30 s of 503s versus "MinIO is up but
 * broken, {@code /api/health} says UP and no WARN is ever emitted". Under-degrading is now the more
 * expensive mistake, so a 5xx and a buried {@code IOException} degrade.
 *
 * <p>Still per-request, deliberately: a 4xx {@link ErrorResponseException} — {@code NoSuchKey},
 * {@code NoSuchBucket}, {@code AccessDenied} — is a healthy MinIO answering about one object, and
 * so is the quota {@code XMinioStorageFull}, which MinIO answers with HTTP <b>507</b> and which is
 * therefore excluded by CODE rather than by status. Likewise the argument/parsing families
 * ({@code IllegalArgumentException}, {@code InvalidKeyException}, {@code XmlParserException},
 * {@code InsufficientDataException}) are bugs in the caller or the SDK.
 */
public final class MinioFailureClassifier {

    /** Cap the cause walk so a self-referential chain cannot spin. */
    private static final int MAX_CAUSE_DEPTH = 10;

    /** S3 codes that mean the server failed, whatever status accompanied them. */
    private static final Set<String> SERVER_SIDE_CODES =
            Set.of("InternalError", "SlowDown", "ServiceUnavailable");

    /**
     * S3 codes that are about THIS request even though MinIO answers them with a 5xx status.
     * {@code XMinioStorageFull} is HTTP 507: the volume is full, which fails writes and leaves
     * reads working — not an unreachable MinIO.
     */
    private static final Set<String> PER_REQUEST_CODES =
            Set.of("XMinioStorageFull", "InsufficientStorage");

    private MinioFailureClassifier() {
    }

    /**
     * @return true when the failure means MinIO itself could not serve the request — the caller
     *         should mark storage degraded. False for a per-request error.
     */
    public static boolean isConnectionLevel(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof ErrorResponseException ere) {
            // An S3 answer. Only a 5xx says the server failed; everything else is about the
            // request, and must not take storage down for the whole fleet.
            return isServerSide(ere);
        }
        if (t instanceof ServerException || t instanceof InvalidResponseException) {
            return true;
        }
        return hasIoCause(t);
    }

    /**
     * Does this S3 answer mean the server failed, or that this request cannot be served?
     *
     * <p>The error CODE decides first and the HTTP status is the fallback, because the two
     * disagree in one case that matters: MinIO answers {@code XMinioStorageFull} with HTTP
     * <b>507</b>. A status-only rule would call a full disk an outage — and since a full MinIO
     * still serves reads, the probe's {@code bucketExists} would heal the flag on the very next
     * tick, so every upload attempt would produce a degrade→recover pair and its WARN+INFO in the
     * operator chat. A full volume is reported by the {@code disk} health component and fails the
     * one request that needs space.
     */
    private static boolean isServerSide(ErrorResponseException e) {
        var errorResponse = e.errorResponse();
        String code = errorResponse == null ? null : errorResponse.code();
        // Null-guarded because Set.of(...).contains(null) throws NPE, and an ErrorResponseException
        // with no parsed body is exactly the case where we most need an answer, not a crash.
        if (code != null) {
            if (PER_REQUEST_CODES.contains(code)) {
                return false;
            }
            // The S3 codes that are 5xx by definition — checked before the status because the SDK
            // does not always carry the okhttp Response through.
            if (SERVER_SIDE_CODES.contains(code)) {
                return true;
            }
        }
        var response = e.response();
        return response != null && response.code() >= 500;
    }

    /** {@link IOException} anywhere in the chain — the socket, however many wrappers deep. */
    private static boolean hasIoCause(Throwable t) {
        Throwable current = t;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof IOException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }
}
