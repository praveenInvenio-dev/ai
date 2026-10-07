package com.aistorystudio.skit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.file.Files; import java.nio.file.Path; import java.time.Duration; import java.time.Instant; import java.util.Map; import java.util.UUID; import java.util.concurrent.ConcurrentHashMap;

@Component
public class FunnySkitJobStore {
 private final Map<UUID,FunnySkitJob> jobs=new ConcurrentHashMap<>(); private final Duration retention;
 public FunnySkitJobStore(@Value("${studio.video-generation.retention-hours:24}") long hours){retention=Duration.ofHours(hours);}
 public FunnySkitJob create(String language){FunnySkitJob j=new FunnySkitJob(UUID.randomUUID(),language); jobs.put(j.getId(),j); return j;}
 public FunnySkitJob get(UUID id){return jobs.get(id);}
 @Scheduled(fixedRate=3600000L) public void cleanup(){Instant cutoff=Instant.now().minus(retention); jobs.entrySet().removeIf(e->{var j=e.getValue(); if(j.getCreatedAt().isBefore(cutoff)){try{if(j.getResultVideoPath()!=null)Files.deleteIfExists(Path.of(j.getResultVideoPath()));}catch(Exception ignored){} return true;} return false;});}
}
