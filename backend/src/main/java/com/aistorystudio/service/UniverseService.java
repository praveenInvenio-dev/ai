package com.aistorystudio.service;

import com.aistorystudio.domain.Universe;
import com.aistorystudio.dto.CreateUniverseRequest;
import com.aistorystudio.repository.UniverseRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class UniverseService {

    private final UniverseRepository universeRepository;

    public UniverseService(UniverseRepository universeRepository) {
        this.universeRepository = universeRepository;
    }

    public Universe create(CreateUniverseRequest req) {
        Universe u = new Universe();
        u.setProjectId(req.projectId());
        u.setName(req.name());
        u.setDescription(req.description());
        u.setVisualStyleJson(req.visualStyle());
        u.setColorPaletteJson(req.colorPalette());
        u.setWorldRulesJson(req.worldRules());
        return universeRepository.save(u);
    }

    public List<Universe> listByProject(UUID projectId) {
        return universeRepository.findByProjectId(projectId);
    }

    public Universe get(UUID id) {
        return universeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Universe not found: " + id));
    }
}
