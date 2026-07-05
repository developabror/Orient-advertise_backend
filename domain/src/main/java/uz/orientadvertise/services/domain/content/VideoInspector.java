package uz.orientadvertise.services.domain.content;

import java.io.InputStream;

public interface VideoInspector {

    /**
     * Deep-inspect video bytes (e.g. via FFprobe). Used after upload to detect
     * corrupt files, missing video streams, password-protected content.
     */
    InspectionResult inspect(InputStream data);

    /**
     * Extract duration from the container metadata (NOT computed from frame count).
     * This is variable-frame-rate safe — VFR videos have unreliable
     * frame-count-based duration, but the container's format-level duration is
     * always correct.
     *
     * @return duration in whole seconds, or 0 if unknown / unreadable
     */
    int extractDurationSeconds(InputStream data);

    sealed interface InspectionResult {
        record Valid() implements InspectionResult {}
        record Invalid(String reason) implements InspectionResult {}

        static InspectionResult valid() { return new Valid(); }
        static InspectionResult invalid(String reason) { return new Invalid(reason); }
    }
}
