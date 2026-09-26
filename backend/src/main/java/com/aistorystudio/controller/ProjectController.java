package com.aistorystudio.controller;

import com.aistorystudio.domain.Project;
import com.aistorystudio.dto.CreateProjectRequest;
import com.aistorystudio.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public Project create(@Valid @RequestBody CreateProjectRequest req) {
        return projectService.create(req);
    }

    @GetMapping
    public List<Project> list() {
        return projectService.list();
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable UUID id) {
        return projectService.get(id);
    }
}
