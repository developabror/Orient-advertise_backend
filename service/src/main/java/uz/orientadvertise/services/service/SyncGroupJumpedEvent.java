package uz.orientadvertise.services.service;

import java.util.List;

/**
 * Published when an operator jumps a whole sync group to a chosen index.
 *
 * <p>Carries the member ids resolved inside the jump's own transaction, because the push is the
 * jump's audience: a device that joined the group microseconds later is not part of this cut-over
 * and picks the group's anchor up on its next {@code /sync} anyway.
 *
 * <p>Listeners MUST subscribe with {@code @TransactionalEventListener(phase = AFTER_COMMIT)}. The
 * push used to fire inside the transaction, so a member that answered it immediately read the
 * override before it was visible and missed the jump entirely (VG-18).
 */
public record SyncGroupJumpedEvent(Long syncGroupId, List<Long> memberIds) {
}
