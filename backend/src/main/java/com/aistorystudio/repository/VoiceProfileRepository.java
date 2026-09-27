package com.aistorystudio.repository;

import com.aistorystudio.domain.VoiceProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VoiceProfileRepository extends JpaRepository<VoiceProfile, UUID> {
    List<VoiceProfile> findAllByOrderByNameAsc();
}
