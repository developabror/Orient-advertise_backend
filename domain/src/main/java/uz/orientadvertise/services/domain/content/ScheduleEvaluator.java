package uz.orientadvertise.services.domain.content;

public interface ScheduleEvaluator {

    /**
     * Inspect every active schedule and apply whatever side-effects are appropriate
     * (e.g. push state to devices, update assignment activations). Should be safe to
     * call on a tight cadence (every minute via Quartz) and on demand (catch-up sweep
     * after downtime).
     *
     * @return a summary record so callers can log / surface metrics
     */
    EvaluationResult evaluateNow();

    record EvaluationResult(int totalSchedules, int activeNow, int errors) {}
}
