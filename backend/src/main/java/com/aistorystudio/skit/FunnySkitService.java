package com.aistorystudio.skit;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.Character;
import com.aistorystudio.domain.CharacterReference;
import com.aistorystudio.repository.CharacterReferenceRepository;
import com.aistorystudio.repository.CharacterRepository;
import com.aistorystudio.provider.MediaProcessor;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.provider.VideoGenerationProvider;
import com.aistorystudio.h3.H3SceneRenderer;
import com.aistorystudio.sequence.ClipMerger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.MediaType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

@Service
public class FunnySkitService {
 private static final Set<String> IMAGE_EXT=Set.of("png","jpg","jpeg","webp");
 private final ProviderGateway gateway; private final StorageProvider storage; private final MediaProcessor media; private final FunnySkitJobStore jobs;
 private final H3SceneRenderer h3SceneRenderer;
 private final WebClient rumik; private final String rumikSpeaker; private final CharacterRepository characterRepository; private final CharacterReferenceRepository characterReferenceRepository; private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
 public FunnySkitService(ProviderGateway gateway, StorageProvider storage, MediaProcessor media, FunnySkitJobStore jobs,
     WebClient.Builder wb, @Value("${studio.audio.rumik.base-url:http://tts-rumik:5006}") String rumikUrl,
     @Value("${studio.audio.rumik.voice:Ira}") String rumikSpeaker, CharacterRepository characterRepository, CharacterReferenceRepository characterReferenceRepository, H3SceneRenderer h3SceneRenderer){this.h3SceneRenderer=h3SceneRenderer;this.gateway=gateway;this.storage=storage;this.media=media;this.jobs=jobs;this.rumik=wb.baseUrl(rumikUrl).build();this.rumikSpeaker=rumikSpeaker;this.characterRepository=characterRepository;this.characterReferenceRepository=characterReferenceRepository;}
 public record JobView(UUID id, FunnySkitJob.Status status, String errorMessage, String language, String script, String[] visualPrompts, String[] dialogues, String[] imageUrls, String soundscape, String resultVideoPath, String characterId, String characterReferenceId){}
 public FunnySkitJob createJob(MultipartFile character, String idea, String language, UUID characterId, UUID referenceId){
   if(idea==null||idea.isBlank()) throw new IllegalArgumentException("Describe the funny skit idea.");
   if(language==null||language.isBlank()) language="Auto-detect";
   if(gateway.localAiVideoUnavailableReason()!=null) throw new IllegalStateException("Video generation is not available: "+gateway.localAiVideoUnavailableReason());
   FunnySkitJob j=jobs.create(language);
   try {
     if(characterId != null) {
       Character c=characterRepository.findById(characterId).orElseThrow(() -> new IllegalArgumentException("Saved character not found: "+characterId));
       CharacterReference ref = referenceId != null
           ? characterReferenceRepository.findById(referenceId).filter(r -> characterId.equals(r.getCharacterId())).orElseThrow(() -> new IllegalArgumentException("Selected character reference does not belong to the selected character."))
           : characterReferenceRepository.findByCharacterId(characterId).stream().filter(CharacterReference::isLocked).findFirst().orElseGet(() -> characterReferenceRepository.findByCharacterId(characterId).stream().filter(CharacterReference::isPrimary).findFirst().orElse(null));
       if(ref == null) throw new IllegalArgumentException("The selected character has no saved reference. Generate a reference in Character Studio first.");
       j.setCharacterId(c.getId().toString()); j.setCharacterReferenceId(ref.getId().toString()); j.setCharacterReferencePath(ref.getImagePath());
       return j;
     }
     if(character==null||character.isEmpty()) throw new IllegalArgumentException("Select a saved character or upload the main focus character image.");
     String n=character.getOriginalFilename()==null?"character.png":character.getOriginalFilename(); int dot=n.lastIndexOf('.'); String ext=dot>0?n.substring(dot+1).toLowerCase(Locale.ROOT):"png";
     if(!IMAGE_EXT.contains(ext)) throw new IllegalArgumentException("Character image must be PNG, JPG, JPEG or WEBP.");
     Path stored=storage.store("funny-skits/uploads/"+j.getId()+"."+ext,character.getBytes()); j.setCharacterReferencePath(stored.toString());
     return j;
   } catch(IOException e){throw new UncheckedIOException(e);}
 }

 @Async("videoGenerationExecutor") public void generateAsync(UUID id, String idea, String language, String tone, String speaker){
   FunnySkitJob job=jobs.get(id); if(job==null)return; job.setStatus(FunnySkitJob.Status.RUNNING);
   Path character=null; List<Path> temp=new ArrayList<>();
   try{
     character=findCharacterReference(id);
     String json=gateway.llm().generateStructured(
       "You are a viral short-form Indian comedy skit writer. Create ONE 15-second skit divided into EXACTLY THREE CONTINUOUS 5-second beats. The setup can be ANY scenario: family, school, office, street, home, friends, relationship, news, absurd, cinematic, etc. An interview is only one possible format, never mandatory. Return JSON only with keys part1VisualPrompt, part1Dialogue, part2VisualPrompt, part2Dialogue, part3VisualPrompt, part3Dialogue, tone, soundscape. Make dialogue short enough for about 5 seconds in the requested language. Part 2 MUST continue the physical action, camera context and emotional state from Part 1. Part 3 MUST continue directly from Part 2 and deliver the punchline/reaction. Keep the uploaded main character as the visual focus. Keep wardrobe, face, age, props, location, lighting and camera style consistent. No subtitles or on-screen text.",
       "Language: "+language+"\nTone: "+(tone==null?"viral reel comedy":tone)+"\nIdea: "+idea+"\nThe uploaded image is the locked identity reference for the main character.");
     var node=parseSkitJsonWithRepair(json, language, tone, idea);
     String[] visuals={node.path("part1VisualPrompt").asText(),node.path("part2VisualPrompt").asText(),node.path("part3VisualPrompt").asText()};
     String[] dialogues={node.path("part1Dialogue").asText(),node.path("part2Dialogue").asText(),node.path("part3Dialogue").asText()};
     String sound=node.path("soundscape").asText("natural ambience, comedic foley, light playful instrumental music");
     for(int i=0;i<3;i++) if(visuals[i].isBlank()||dialogues[i].isBlank()) throw new IllegalStateException("Ollama did not return all three 5-second skit parts.");
     job.setVisualPrompts(visuals); job.setDialogues(dialogues); job.setSoundscape(sound); job.setScript(dialogues[0]+" | "+dialogues[1]+" | "+dialogues[2]);
     generateStoryboardImages(job, character, visuals, temp);
     job.setStatus(FunnySkitJob.Status.SUCCEEDED);
   }catch(Exception e){job.setErrorMessage(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());job.setStatus(FunnySkitJob.Status.FAILED);}
   finally{for(Path p:temp)cleanup(p);}
 }

 private void generateStoryboardImages(FunnySkitJob job, Path character, String[] visuals, List<Path> temp) throws Exception {
   String[] stored=new String[3];
   Path previous=character;
   for(int i=0;i<3;i++){
     String continuity=i==0?"Opening frame of the skit. Establish the situation clearly.":"Continuation frame. Continue directly from the previous storyboard image; preserve the same character identity, wardrobe, location, props, lighting and camera context. Do not reset the scene.";
     var req=new com.aistorystudio.provider.ImageGenerationProvider.ImageGenerationRequest(
       "Vertical 9:16 viral comedy storyboard frame. "+visuals[i]+" "+continuity+" The uploaded character is locked and must remain visually identical. Natural realistic social-media video frame, expressive comedic acting, no text, no subtitles.",
       "deformed face, identity drift, extra limbs, duplicate character, text, subtitles, watermark, logo",768,1344,30,0,null,"qwen-image-2-1-16gb-ref",null,character.toString(),i==0?null:previous.toString());
     var result=gateway.generateImage(req);
     Path img=storage.store("funny-skits/jobs/"+job.getId()+"/scene-"+(i+1)+".png",result.imageBytes());
     stored[i]=img.toString(); previous=img;
   }
   job.setImagePaths(stored);
 }

 public void regenerateImage(UUID id, int scene) {
   FunnySkitJob job=jobs.get(id); if(job==null) throw new IllegalArgumentException("Skit job not found");
   if(scene<1||scene>3) throw new IllegalArgumentException("Scene must be 1, 2 or 3.");
   try{
     Path character=findCharacterReference(id); Path previous=scene==1?character:Path.of(job.getImagePaths()[scene-2]);
     String visual=job.getVisualPrompts()[scene-1];
     String continuity=scene==1?"Opening frame.":"Continue directly from the previous storyboard image. Preserve identity, wardrobe, location, props, lighting and camera context.";
     var req=new com.aistorystudio.provider.ImageGenerationProvider.ImageGenerationRequest("Vertical 9:16 viral comedy storyboard frame. "+visual+" "+continuity+" Locked main character, realistic social-media frame, expressive comedic acting, no text.","deformed face, identity drift, extra limbs, duplicate character, text, subtitles, watermark",768,1344,30,0,null,"qwen-image-2-1-16gb-ref",null,character.toString(),scene==1?null:previous.toString());
     var result=gateway.generateImage(req);
     Path img=storage.store("funny-skits/jobs/"+id+"/scene-"+scene+"-regen-"+System.currentTimeMillis()+".png",result.imageBytes());
     String[] paths=job.getImagePaths().clone(); paths[scene-1]=img.toString(); for(int i=scene;i<3;i++) paths[i]=null; job.setImagePaths(paths);
   }catch(Exception e){throw new IllegalStateException("Could not regenerate scene "+scene+": "+e.getMessage(),e);}
 }

 /** Kept for callers that do not pass a speech engine: H3 speaks. */
 public void renderApprovedAsync(UUID id, String tone, String speaker){ renderApprovedAsync(id, tone, speaker, "H3"); }

 /**
  * Same shared H3 scene renderer as Video Generation / Story Video Production / Storyboard.
  * Each approved storyboard frame becomes one part; the skit lines are on-screen dialogue
  * (lip-synced). speechEngine H3 = H3 speaks; INDIC_TTS = IndicF5 voice speaks and H3 lip-syncs
  * to it (character sheet passed as identity reference), ambience/SFX/music from H3.
  */
 @Async("videoGenerationExecutor") public void renderApprovedAsync(UUID id, String tone, String speaker, String speechEngine){
   FunnySkitJob job=jobs.get(id); if(job==null)return; job.setStatus(FunnySkitJob.Status.RUNNING);
   Path work=null;
   try{
     Path character=findCharacterReference(id);
     work=Files.createTempDirectory("skit-h3-"+id+"-");
     H3SceneRenderer.SpeechEngine engine=H3SceneRenderer.SpeechEngine.parse(speechEngine);
     List<Path> parts=new ArrayList<>();
     for(int i=0;i<3;i++){
       Path storyboard=Path.of(job.getImagePaths()[i]);
       List<H3SceneRenderer.Line> lines=skitLines(job.getDialogues()[i], tone);
       String visual=job.getVisualPrompts()[i]+". Part "+(i+1)+" of 3 of a short comedy skit; expressive comedic acting and timing.";
       String sound=job.getSoundscape()==null||job.getSoundscape().isBlank()?null:"Ambience: "+job.getSoundscape();
       var spec=new H3SceneRenderer.SceneSpec(visual,null,tone,"natural realistic social-media video",job.getLanguage(),
           lines,null,sound,5.0,"skit-"+id,engine);
       var r=h3SceneRenderer.render(spec,new H3SceneRenderer.Options(storyboard,0,0,work.resolve("part-"+(i+1)),()->false,character));
       Path part=work.resolve("skit-part-"+(i+1)+".mp4"); Files.copy(r.video(),part,java.nio.file.StandardCopyOption.REPLACE_EXISTING); parts.add(part);
     }
     Path out=work.resolve("funny-skit.mp4");
     int[] wh=firstSize(parts.get(0));
     ClipMerger.merge(parts,out,wh[0],wh[1],0);
     Path stored=storage.store("funny-skits/results/"+id+".mp4",Files.readAllBytes(out));
     job.setResultVideoPath(stored.toString()); job.setStatus(FunnySkitJob.Status.SUCCEEDED);
   }catch(Exception e){job.setErrorMessage(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());job.setStatus(FunnySkitJob.Status.FAILED);}
   finally{ if(work!=null) deleteTree(work); }
 }

 /** Skit lines are spoken on screen. "Name: line" keeps the name; plain text = the main character. */
 private List<H3SceneRenderer.Line> skitLines(String dialogue, String tone){
   List<H3SceneRenderer.Line> out=new ArrayList<>();
   for(H3SceneRenderer.Line l:H3SceneRenderer.linesFromText(null,dialogue)){
     String speaker=l.voiceOver()?"Main character":l.speaker();
     String delivery=l.delivery()==null||l.delivery().isBlank()?tone:l.delivery();
     out.add(new H3SceneRenderer.Line(speaker,l.text(),delivery,false,0,null));
   }
   if(out.isEmpty()&&dialogue!=null&&!dialogue.isBlank()) out.add(new H3SceneRenderer.Line("Main character",dialogue,tone,false,0,null));
   return out;
 }
 private int[] firstSize(Path video){
   try{ Process p=new ProcessBuilder("ffprobe","-v","error","-select_streams","v:0","-show_entries","stream=width,height","-of","csv=p=0",video.toString()).redirectErrorStream(true).start();
     String[] wh=new String(p.getInputStream().readAllBytes()).trim().split(","); p.waitFor();
     int w=Integer.parseInt(wh[0].trim()), h=Integer.parseInt(wh[1].trim()); return new int[]{w-(w%2),h-(h%2)};
   }catch(Exception e){ return new int[]{480,832}; }
 }
 private void deleteTree(Path dir){
   try(var walk=Files.walk(dir)){ walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuiet); }catch(Exception ignored){}
 }
 private void extractLastFrame(Path video,Path out)throws Exception{
   Process p=new ProcessBuilder("ffmpeg","-y","-sseof","-0.08","-i",video.toString(),"-frames:v","1","-vf","scale=480:-2","-update","1",out.toString()).redirectErrorStream(true).start();
   p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
   if(!p.waitFor(3,java.util.concurrent.TimeUnit.MINUTES)||p.exitValue()!=0||!Files.exists(out)||Files.size(out)<256) throw new IllegalStateException("Could not extract continuation frame from skit part.");
 }
 private String cleanSpeechText(String text){
   if(text==null) return "";
   return text.replaceAll("\\[(?:gasp|laugh|chuckle|giggle|sigh|surprise|laughter|laughing|crying|breath|scream)\\]", "")
       .replaceAll("\\s{2,}", " ").trim();
 }

 private byte[] synthesizeSkitSpeech(String text,String language,String tone,String speaker){
   var request=new com.aistorystudio.provider.TextToSpeechProvider.TtsRequest(text, speaker==null||speaker.isBlank()?null:speaker, language, 1.0, 1.0, tone, 0.85, "natural conversational comedy delivery", java.util.List.of(), true, null, "Expressive Indian comedy performance; natural pauses; conversational timing; clear pronunciation; do not sound like a voice-over; keep the same character voice.");
   return gateway.synthesizeForStoryLanguage(request).audioBytes();
 }
 private byte[] rumikSpeech(String text,String language,String tone,String speaker){
   String sp=speaker==null||speaker.isBlank()?rumikSpeaker:speaker; Map<String,Object> body=new LinkedHashMap<>(); body.put("speaker",sp); body.put("input",text); body.put("description",description(language,tone)); body.put("temperature",0.62); body.put("top_k",20); body.put("max_new_tokens",2200);
   return rumik.post().uri("/v1/audio/speech").contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(byte[].class).block(Duration.ofMinutes(5));
 }
 private String description(String language,String tone){String t=tone==null?"excited":tone; String accent=switch(language.toLowerCase(Locale.ROOT)){case "hindi"->"Hindi accent";case "telugu"->"Telugu accent";case "tamil"->"Tamil accent";case "kannada"->"Kannada accent";case "bengali"->"Bengali accent";case "punjabi"->"Punjabi accent";case "indian english","english"->"Indian English accent";default->language+" speech";}; return t+", "+accent+", natural conversational comedy delivery, expressive but clear, consistent voice identity, precise pronunciation, natural pauses, do not read bracketed sound-effect markers";}
 private Path findCharacterReference(UUID id)throws IOException{
   FunnySkitJob job=jobs.get(id);
   if(job==null || job.getCharacterReferencePath()==null || job.getCharacterReferencePath().isBlank()) throw new IllegalStateException("Character reference is not available for this skit.");
   Path p=Path.of(job.getCharacterReferencePath());
   if(!Files.exists(p)) throw new IllegalStateException("Character reference file is missing: "+p);
   return p;
 }

 private com.fasterxml.jackson.databind.JsonNode parseSkitJsonWithRepair(String raw, String language, String tone, String idea) throws Exception {
   try { return objectMapper.readTree(raw); }
   catch(Exception first) {
     String repairPrompt="Return ONLY one valid JSON object. No Markdown fences, no commentary, no backticks. Required keys: part1VisualPrompt, part1Dialogue, part2VisualPrompt, part2Dialogue, part3VisualPrompt, part3Dialogue, tone, soundscape. Language: "+language+". Tone: "+(tone==null?"viral reel comedy":tone)+". Idea: "+idea+". Keep each dialogue short enough for 5 seconds and make parts continuous.";
     String repaired=gateway.llm().generateStructured("You are a strict JSON repair engine. Output raw JSON only.", repairPrompt);
     return objectMapper.readTree(repaired);
   }
 }
 private void sliceAudio(Path in,Path out,double start,double dur)throws Exception{
   Process p=new ProcessBuilder("ffmpeg","-y","-ss",String.valueOf(start),"-i",in.toString(),"-t",String.valueOf(dur),"-ar","24000","-ac","1",out.toString()).redirectErrorStream(true).start();
   p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
   if(!p.waitFor(5,java.util.concurrent.TimeUnit.MINUTES)||p.exitValue()!=0) throw new IllegalStateException("Could not split Rumik audio into a skit segment.");
 }
 private void cleanup(Path...paths){for(Path p:paths)try{Files.deleteIfExists(p);}catch(Exception ignored){}}
 private void cleanup(Path a,Path b,Path c,Path d,List<Path> clips){cleanup(a,b,c,d);clips.forEach(this::deleteQuiet);}
 private void deleteQuiet(Path p){try{Files.deleteIfExists(p);}catch(Exception ignored){}}
 public JobView status(UUID id){var j=jobs.get(id); if(j==null)throw new IllegalArgumentException("Skit job not found"); String[] urls=new String[3]; String[] p=j.getImagePaths(); for(int i=0;i<3;i++) urls[i]=(p!=null&&p[i]!=null)?"/api/funny-skits/jobs/"+id+"/images/"+(i+1):null; return new JobView(j.getId(),j.getStatus(),j.getErrorMessage(),j.getLanguage(),j.getScript(),j.getVisualPrompts(),j.getDialogues(),urls,j.getSoundscape(),j.getResultVideoPath(),j.getCharacterId(),j.getCharacterReferenceId());}
 public Path image(UUID id,int scene){var j=jobs.get(id);if(j==null||scene<1||scene>3||j.getImagePaths()[scene-1]==null)throw new IllegalStateException("Storyboard image is not ready.");return Path.of(j.getImagePaths()[scene-1]);}
 public Path result(UUID id){var j=jobs.get(id);if(j==null||j.getStatus()!=FunnySkitJob.Status.SUCCEEDED||j.getResultVideoPath()==null)throw new IllegalStateException("Skit is not ready yet.");return Path.of(j.getResultVideoPath());}
}
