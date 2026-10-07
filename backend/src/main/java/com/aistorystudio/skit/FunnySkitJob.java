package com.aistorystudio.skit;

import java.time.Instant;
import java.util.UUID;

public class FunnySkitJob {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }
    private final UUID id; private final Instant createdAt;
    private volatile Status status = Status.QUEUED;
    private volatile String errorMessage; private volatile String resultVideoPath;
    private volatile String language; private volatile String script; private volatile String[] visualPrompts = new String[3]; private volatile String[] dialogues = new String[3]; private volatile String[] imagePaths = new String[3]; private volatile String soundscape;
    public FunnySkitJob(UUID id, String language) { this.id=id; this.language=language; this.createdAt=Instant.now(); }
    public UUID getId(){return id;} public Instant getCreatedAt(){return createdAt;}
    public Status getStatus(){return status;} public void setStatus(Status s){status=s;}
    public String getErrorMessage(){return errorMessage;} public void setErrorMessage(String s){errorMessage=s;}
    public String getResultVideoPath(){return resultVideoPath;} public void setResultVideoPath(String s){resultVideoPath=s;}
    public String getLanguage(){return language;} public String getScript(){return script;} public void setScript(String s){script=s;}
    public String[] getVisualPrompts(){return visualPrompts;} public void setVisualPrompts(String[] v){visualPrompts=v;}
    public String[] getDialogues(){return dialogues;} public void setDialogues(String[] v){dialogues=v;}
    public String[] getImagePaths(){return imagePaths;} public void setImagePaths(String[] v){imagePaths=v;}
    public String getSoundscape(){return soundscape;} public void setSoundscape(String v){soundscape=v;}
}
