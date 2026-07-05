package uz.orientadvertise.services.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.ProjectOperator;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ProjectOperatorRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;

/**
 * Manages OPERATOR ↔ project assignments (the {@code project_operator} table). Single-grant
 * primitives ({@link #assignOperator}/{@link #unassignOperator}) back the POST/DELETE
 * endpoints; {@link #setOperators} backs the bulk PUT used by the project-edit screen.
 *
 * <p>Operator visibility is governed by these rows — see {@link OperatorScopeResolver}.
 * Methods returning {@link ProjectOperator} (not {@code AppUser}) so the controller can map
 * all four {@code OperatorRef} fields, including the {@code assignedAt}/{@code assignedBy} audit.
 */
@Service
public class ProjectOperatorService {

    private static final Logger log = LoggerFactory.getLogger(ProjectOperatorService.class);

    private final ProjectOperatorRepository projectOperatorRepository;
    private final ProjectRepository projectRepository;
    private final AppUserRepository userRepository;

    public ProjectOperatorService(ProjectOperatorRepository projectOperatorRepository,
                                  ProjectRepository projectRepository,
                                  AppUserRepository userRepository) {
        this.projectOperatorRepository = projectOperatorRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<ProjectOperator> listOperators(Long projectId) {
        requireProject(projectId);
        return projectOperatorRepository.findOperatorsByProjectId(projectId);
    }

    @Transactional
    public ProjectOperator assignOperator(Long projectId, Long userId, String assignedBy) {
        Project project = requireProject(projectId);
        AppUser user = requireOperatorUser(userId);
        if (projectOperatorRepository.existsByUserIdAndProjectId(userId, projectId)) {
            throw new IllegalStateException(
                    "User %d is already assigned to project %d".formatted(userId, projectId));
        }
        return projectOperatorRepository.save(new ProjectOperator(project, user, assignedBy));
    }

    /** Idempotent — removing a non-existent assignment returns silently (204). 404 only for unknown project. */
    @Transactional
    public void unassignOperator(Long projectId, Long userId) {
        requireProject(projectId);
        projectOperatorRepository.findByUserIdAndProjectId(userId, projectId)
                .ifPresent(projectOperatorRepository::delete);
    }

    /**
     * Bulk "set the whole operator set for this project": validate every id is an OPERATOR
     * (else 404 unknown / 409 wrong-role), diff against the current set, add/remove to match,
     * and return the full resulting set.
     */
    @Transactional
    public List<ProjectOperator> setOperators(Long projectId, List<Long> userIds, String assignedBy) {
        Project project = requireProject(projectId);
        Set<Long> desired = new LinkedHashSet<>(userIds == null ? List.of() : userIds);

        // Validate all up-front so a bad id rolls back the whole change (no partial set).
        for (Long userId : desired) {
            requireOperatorUser(userId);
        }

        List<ProjectOperator> current = projectOperatorRepository.findOperatorsByProjectId(projectId);
        Set<Long> currentUserIds = new LinkedHashSet<>();
        for (ProjectOperator po : current) {
            Long uid = po.getUser().getId();
            currentUserIds.add(uid);
            if (!desired.contains(uid)) {
                projectOperatorRepository.delete(po);   // remove operators no longer wanted
            }
        }
        for (Long userId : desired) {
            if (!currentUserIds.contains(userId)) {
                AppUser user = requireOperatorUser(userId);
                projectOperatorRepository.save(new ProjectOperator(project, user, assignedBy));
            }
        }
        return projectOperatorRepository.findOperatorsByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public List<Long> listProjectIds(Long userId) {
        return projectOperatorRepository.findProjectIdsByUserId(userId);
    }

    /** Bulk-remove every assignment for the user — called on operator delete. */
    @Transactional
    public int revokeAllForUser(Long userId) {
        int removed = projectOperatorRepository.deleteAllByUserId(userId);
        if (removed > 0) {
            log.info("Cascaded {} project-operator assignments on operator deletion [userId={}]", removed, userId);
        }
        return removed;
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }

    private AppUser requireOperatorUser(Long userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getRole() != Role.OPERATOR) {
            throw new IllegalStateException("User %d is not an OPERATOR".formatted(userId));
        }
        return user;
    }
}
