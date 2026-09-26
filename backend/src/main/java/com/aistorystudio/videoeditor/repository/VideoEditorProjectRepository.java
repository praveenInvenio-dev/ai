package com.aistorystudio.videoeditor.repository;

import com.aistorystudio.videoeditor.domain.VideoEditorProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VideoEditorProjectRepository extends JpaRepository<VideoEditorProject, UUID> {
    List<VideoEditorProject> findAllByOrderByUpdatedAtDesc();
}
