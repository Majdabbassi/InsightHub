package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.CreateProjectRequest;
import com.dataanalytics.backend.dto.ProjectResponse;
import com.dataanalytics.backend.dto.UpdateProjectRequest;
import com.dataanalytics.backend.exception.ForbiddenException;
import com.dataanalytics.backend.exception.ProjectNotFoundException;
import com.dataanalytics.backend.model.Project;
import com.dataanalytics.backend.model.User;
import com.dataanalytics.backend.repository.ProjectRepository;
import com.dataanalytics.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    @Transactional
    public ProjectResponse createProject(String ownerEmail, CreateProjectRequest request) {
        User owner = getUserByEmail(ownerEmail);

        Project project = Project.builder()
                .name(request.getName())
                .description(request.getDescription())
                .owner(owner)
                .build();

        return ProjectResponse.from(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public Page<ProjectResponse> listProjects(String ownerEmail, Pageable pageable) {
        User owner = getUserByEmail(ownerEmail);
        return projectRepository.findByOwnerId(owner.getId(), pageable).map(ProjectResponse::from);
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(String ownerEmail, Long projectId) {
        return ProjectResponse.from(findOwnedProject(ownerEmail, projectId));
    }

    @Transactional
    public ProjectResponse updateProject(String ownerEmail, Long projectId, UpdateProjectRequest request) {
        Project project = findOwnedProject(ownerEmail, projectId);
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        return ProjectResponse.from(projectRepository.save(project));
    }

    @Transactional
    public void deleteProject(String ownerEmail, Long projectId) {
        Project project = findOwnedProject(ownerEmail, projectId);
        projectRepository.delete(project);
    }

    /**
     * Loads the project and verifies it exists and belongs to the given user.
     * Shared with DatasetService for chained ownership checks. Read-only
     * because it touches the lazy {@code owner} association; the returned
     * entity becomes detached once the method returns.
     */
    @Transactional(readOnly = true)
    public Project findOwnedProject(String ownerEmail, Long projectId) {
        User user = getUserByEmail(ownerEmail);

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        if (!project.getOwner().getId().equals(user.getId())) {
            throw new ForbiddenException("You do not have access to this project");
        }
        return project;
    }

    private User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
    }
}
