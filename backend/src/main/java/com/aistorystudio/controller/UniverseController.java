package com.aistorystudio.controller;

import com.aistorystudio.domain.Universe;
import com.aistorystudio.dto.CreateUniverseRequest;
import com.aistorystudio.service.UniverseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/universes")
public class UniverseController {

    private final UniverseService universeService;

    public UniverseController(UniverseService universeService) {
        this.universeService = universeService;
    }

    @PostMapping
    public Universe create(@Valid @RequestBody CreateUniverseRequest req) {
        return universeService.create(req);
    }

    @GetMapping
    public List<Universe> listByProject(@RequestParam UUID projectId) {
        return universeService.listByProject(projectId);
    }

    @GetMapping("/{id}")
    public Universe get(@PathVariable UUID id) {
        return universeService.get(id);
    }
}
