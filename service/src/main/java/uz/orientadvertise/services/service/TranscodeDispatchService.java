package uz.orientadvertise.services.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.orientadvertise.services.domain.content.Transcoder;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

/**
 * The single "claim, then dispatch" step. Every path that can start a transcode goes through here:
 * the after-commit upload listener, the periodic {@link TranscodeSweeper}, and the operator-driven
 * retranscode endpoint.
 *
 * <p><b>Claim before dispatch, always.</b> The conditional UPDATE is what makes double-dispatch
 * impossible without a distributed lock — of N callers racing on one row, exactly one sees a
 * rows-affected of 1. That matters because the sweeper runs every couple of minutes against rows
 * that a live dispatch may be about to pick up; without the compare-and-set, a slow upload and a
 * sweep would both start an encode, doubling peak memory on a host sized for one.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: the claim is a self-committing statement on the
 * repository, and dispatching from inside a transaction is the exact bug this release fixes.
 */
@Service
public class TranscodeDispatchService {

    private static final Logger log = LoggerFactory.getLogger(TranscodeDispatchService.class);

    private final ContentFileRepository contentFileRepository;
    private final Transcoder transcoder;

    public TranscodeDispatchService(ContentFileRepository contentFileRepository, Transcoder transcoder) {
        this.contentFileRepository = contentFileRepository;
        this.transcoder = transcoder;
    }

    /**
     * Claim {@code id} out of {@code expected} and, only if the claim was won, hand it to the
     * transcode pool.
     *
     * @param expected the status the row is believed to be in — the compare half of the CAS
     * @param urgent   whether to enqueue at the front of the transcode queue
     * @return {@code true} if this call won the claim and dispatched; {@code false} if another
     *         caller already owns the row, or it changed state / was deleted in between
     */
    public boolean dispatch(Long contentFileId, ContentFile.Status expected, boolean urgent) {
        int claimed = contentFileRepository.claimForTranscode(contentFileId, expected, Instant.now());
        if (claimed != 1) {
            log.debug("Transcode claim lost [id={}, expected={}] — another worker owns the row "
                    + "or it moved on", contentFileId, expected);
            return false;
        }

        if (urgent) {
            transcoder.transcodeAsyncUrgent(contentFileId);
        } else {
            transcoder.transcodeAsync(contentFileId);
        }
        return true;
    }
}
