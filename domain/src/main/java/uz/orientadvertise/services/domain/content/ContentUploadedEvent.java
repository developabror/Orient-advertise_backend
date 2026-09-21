package uz.orientadvertise.services.domain.content;

/**
 * Published by {@code ContentUploadService} once a {@code ContentFile} row has been persisted.
 *
 * <p><b>Listeners MUST subscribe with {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.</b>
 * That is the entire reason this event exists. Before v1.0.132 the upload service called the
 * transcoder directly, ten lines after {@code save()} and still inside its own {@code @Transactional}
 * boundary. {@code ContentFile} uses {@code GenerationType.IDENTITY}, so Hibernate had issued the
 * INSERT to obtain the id — but the row was uncommitted, and under READ COMMITTED a plain SELECT on
 * another connection takes no lock against a foreign uncommitted INSERT. Whichever finished first —
 * (executor handoff + connection acquire + SELECT) or (transaction unwind + COMMIT + WAL fsync) —
 * decided the outcome. On the production box the executor won 2-of-2 and both files were stuck in
 * {@code UPLOADED} forever, because the pipeline's only reaction to "row not found" was a WARN.
 *
 * <p>The fix is to dispatch after commit, never to sleep, retry {@code findById}, or yield.
 *
 * @param contentFileId id of the freshly persisted row — guaranteed visible to other transactions
 *                      by the time an AFTER_COMMIT listener runs
 * @param urgent        whether the upload asked to jump the transcode queue
 */
public record ContentUploadedEvent(Long contentFileId, boolean urgent) {
}
