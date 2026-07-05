package uz.orientadvertise.services.domain.content;

public interface Transcoder {

    /**
     * Trigger asynchronous transcoding of an uploaded file. Implementations should
     * return immediately and process out-of-band (e.g. via @Async or a queue).
     */
    void transcodeAsync(Long contentFileId);

    /**
     * Front-of-queue variant for urgent uploads. Uses a separate executor so it
     * does NOT wait behind backlogged normal jobs. Same end-state as the regular
     * pipeline — the only difference is scheduling priority.
     */
    void transcodeAsyncUrgent(Long contentFileId);
}
