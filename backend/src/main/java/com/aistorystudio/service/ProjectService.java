package com.aistorystudio.service;

import com.aistorystudio.domain.Project;
import com.aistorystudio.dto.CreateProjectRequest;
import com.aistorystudio.repository.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public Project create(CreateProjectRequest req) {
        Project p = new Project();
        p.setName(req.name());
        p.setDescription(req.description());
        return projectRepository.save(p);
    }

    public List<Project> list() {
        return projectRepository.findAll();
    }

    public Project get(UUID id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
    }
}
