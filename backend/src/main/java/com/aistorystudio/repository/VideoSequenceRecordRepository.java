package com.aistorystudio.repository;

import com.aistorystudio.domain.VideoSequenceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VideoSequenceRecordRepository extends JpaRepository<VideoSequenceRecord, UUID> {
    List<VideoSequenceRecord> findAllByOrderByCreatedAtDesc();
    List<VideoSequenceRecord> findByExpiresAtBefore(Instant cutoff);
}
