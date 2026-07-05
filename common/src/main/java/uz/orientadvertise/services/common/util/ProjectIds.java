package uz.orientadvertise.services.common.util;

/**
 * Single source of truth for the project-id sentinel convention shared across the write
 * path (upload / re-bind) and the read path (content listing).
 */
public final class ProjectIds {

    private ProjectIds() {
    }

    /**
     * Coerce sentinel/invalid project ids to {@code null}. Postgres ids start at 1, so any
     * value {@code <= 0} — notably the {@code -1} "Unassigned" sentinel the FE sends for a
     * playlist bound to the seeded Unassigned project (see {@code V13__device_registration})
     * — is the caller saying "I don't have a real project."
     *
     * <p>Every caller treats {@code null} as "no project": orphan content on the write path,
     * "no project filter" on the read path. Normalizing here keeps that business rule in one
     * place instead of leaking {@code <= 0} comparisons into JPQL.
     */
    public static Long normalize(Long projectId) {
        return (projectId == null || projectId <= 0) ? null : projectId;
    }
}
