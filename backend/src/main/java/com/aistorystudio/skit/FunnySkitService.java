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
 @org.springframework.beans.factory.annotation.Autowired private AdventureSeriesStore seriesStore;
 public record JobView(UUID id, FunnySkitJob.Status status, String errorMessage, String language, String script, String[] visualPrompts, String[] dialogues, String[] imageUrls, String soundscape, String resultVideoPath, String characterId, String characterReferenceId,
     String mode, String title, String engine, String button, String seriesId, int episodeNumber, String summary, String cliffhanger, String stage){}
 /** Visual style of the skit (storyboard frames + H3 video). */
 static final java.util.Map<String,String> STYLES=new java.util.LinkedHashMap<>();
 static {
   STYLES.put("realistic","photorealistic smartphone video frame, natural light, real skin texture, believable everyday location");
   STYLES.put("cinematic","cinematic film still, anamorphic lens, dramatic motivated lighting, shallow depth of field, rich colour grade");
   STYLES.put("cartoon3d","Pixar-style 3D animated film frame, soft global illumination, expressive stylised characters, vibrant colours");
   STYLES.put("anime","Japanese anime cel-shaded frame, clean line art, vibrant colours, expressive anime faces and reactions");
   STYLES.put("claymation","claymation stop-motion frame, handmade clay textures, miniature set, warm practical lighting");
   STYLES.put("comic","comic-book illustration, bold ink outlines, halftone shading, vivid flat colours, dynamic panels");
   // the adventure micro-series look (golden-hour fantasy jungle temples, cinematic child explorer)
   STYLES.put("adventure","cinematic fantasy adventure film frame, photoreal with a gentle animated-film charm, golden-hour volumetric light, god rays through drifting mist, ancient jungle temples and waterfalls, lush depth, 35mm anamorphic lens, shallow depth of field, warm rich colour grade");
 }
 static String styleId(String v){ String k=v==null?"realistic":v.trim().toLowerCase(Locale.ROOT); return STYLES.containsKey(k)?k:"realistic"; }
 private static String styleText(FunnySkitJob j){ return STYLES.get(styleId(j.getStyle())); }

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
     com.fasterxml.jackson.databind.JsonNode node = job.getMode()==FunnySkitJob.Mode.ADVENTURE ? writeEpisode(job) : writersRoom(job, idea, language, tone);
     String[] visuals={node.path("part1VisualPrompt").asText(),node.path("part2VisualPrompt").asText(),node.path("part3VisualPrompt").asText()};
     String[] dialogues={node.path("part1Dialogue").asText(""),node.path("part2Dialogue").asText(""),node.path("part3Dialogue").asText("")};
     String sound=node.path("soundscape").asText(job.getMode()==FunnySkitJob.Mode.ADVENTURE
         ? "sweeping cinematic orchestral score building to a swell, jungle ambience, distant waterfall, soft wind"
         : "natural ambience, comedic foley, light playful instrumental music");
     for(int i=0;i<3;i++) if(visuals[i].isBlank()) throw new IllegalStateException("The writer did not return all three 5-second parts.");
     if(job.getMode()==FunnySkitJob.Mode.SKIT) for(int i=0;i<3;i++) if(dialogues[i].isBlank()) throw new IllegalStateException("The writer left part "+(i+1)+" without dialogue.");
     job.setVisualPrompts(visuals); job.setDialogues(dialogues); job.setSoundscape(sound);
     job.setTitle(node.path("title").asText(null)); job.setEngine(node.path("engine").asText(null)); job.setButton(node.path("button").asText(null));
     job.setSummary(node.path("summary").asText(null)); job.setCliffhanger(node.path("cliffhanger").asText(null));
     job.setScript(String.join(" | ", Arrays.stream(dialogues).filter(d->!d.isBlank()).toList()));
     job.setStage("Drawing the three storyboard frames");
     generateStoryboardImages(job, character, visuals, temp);
     if(job.getMode()==FunnySkitJob.Mode.ADVENTURE) updateEpisode(job, "STORYBOARD", null, null);
     job.setStage(null);
     job.setStatus(FunnySkitJob.Status.SUCCEEDED);
   }catch(Exception e){job.setErrorMessage(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());job.setStatus(FunnySkitJob.Status.FAILED);
     if(job.getMode()==FunnySkitJob.Mode.ADVENTURE) updateEpisode(job, "FAILED", null, null);}
   finally{for(Path p:temp)cleanup(p);}
 }

 /**
  * Comedy writers' room: PITCH five different angles and pick the funniest, WRITE it as three continuous beats,
  * PUNCH UP every weak line. If a later pass fails, the previous pass's result is used (never worse than one-shot).
  */
 private com.fasterxml.jackson.databind.JsonNode writersRoom(FunnySkitJob job, String idea, String language, String tone) throws Exception {
   String style=styleText(job);
   String chosen="{}";
   try{
     job.setStage("Writers' room: pitching 5 comic angles");
     var pitch=objectMapper.readTree(gateway.llm().generateStructured(SkitWritersRoom.pitchSystemPrompt(), SkitWritersRoom.pitchUserPrompt(idea,language,tone,style)));
     var angles=pitch.path("angles");
     int best=pitch.path("chosen").asInt(-1);
     if(best<0||best>=angles.size()){ // fall back to the highest self-score
       double top=-1; for(int i=0;i<angles.size();i++){var a=angles.get(i); double sc=a.path("surprise").asDouble(0)+a.path("relatable").asDouble(0)+a.path("fits15s").asDouble(0); if(sc>top){top=sc;best=i;}}
     }
     if(best>=0&&best<angles.size()) chosen=angles.get(best).toString();
   }catch(Exception e){ chosen="{\"premise\":"+objectMapper.writeValueAsString(idea)+"}"; }
   job.setStage("Writers' room: writing the skit");
   String draftRaw=gateway.llm().generateStructured(SkitWritersRoom.writeSystemPrompt(), SkitWritersRoom.writeUserPrompt(idea,language,tone,style,chosen));
   var draft=parseSkitJsonWithRepair(draftRaw, language, tone, idea);
   try{
     job.setStage("Writers' room: punch-up pass");
     var punched=objectMapper.readTree(gateway.llm().generateStructured(SkitWritersRoom.punchUpSystemPrompt(),
         "Spoken language: "+language+"\nComedy style: "+(tone==null?"viral reel comedy":tone)+"\nSkit:\n"+draft.toString()));
     boolean complete=true; for(int i=1;i<=3;i++) if(punched.path("part"+i+"VisualPrompt").asText("").isBlank()||punched.path("part"+i+"Dialogue").asText("").isBlank()) complete=false;
     if(complete) return punched;
   }catch(Exception ignored){ }
   return draft;
 }

 // ================================================================== adventure series

 /** Start a series: lock the hero, write the bible (async), then episode 1 can be created. */
 public AdventureSeriesStore.Series createSeries(MultipartFile character, String premise, String language, String style, UUID characterId, UUID referenceId, boolean narration){
   if(premise==null||premise.isBlank()) throw new IllegalArgumentException("Describe the series premise.");
   FunnySkitJob tmp=createJob(character, premise, language, characterId, referenceId);   // reuses the character checks / upload
   var s=seriesStore.create();
   s.premise=premise.trim(); s.language=language==null||language.isBlank()?"English":language; s.style=styleId(style==null?"adventure":style); s.narration=narration;
   s.characterId=tmp.getCharacterId(); s.characterReferenceId=tmp.getCharacterReferenceId(); s.characterReferencePath=tmp.getCharacterReferencePath();
   s.title="New series";
   seriesStore.save(s);
   return s;
 }

 @Async("videoGenerationExecutor") public void writeBibleAsync(String seriesId){
   var s=seriesStore.get(seriesId);
   try{
     var bible=objectMapper.readTree(gateway.llm().generateStructured(SkitWritersRoom.bibleSystemPrompt(), SkitWritersRoom.bibleUserPrompt(s.premise,s.language,STYLES.get(s.style))));
     s.bibleJson=bible.toString(); s.title=bible.path("title").asText(s.premise); s.logline=bible.path("logline").asText(null);
   }catch(Exception e){ s.bibleJson="{\"title\":"+quote(s.premise)+",\"logline\":"+quote(s.premise)+"}"; s.title=s.premise; }
   seriesStore.save(s);
 }

 /** Next episode: a skit job in ADVENTURE mode that continues from the last finished episode. */
 public FunnySkitJob startEpisode(String seriesId, String direction){
   var s=seriesStore.get(seriesId);
   for(var e:s.episodes) if("WRITING".equals(e.status)||"RENDERING".equals(e.status)) throw new IllegalStateException("Episode "+e.number+" is still being made.");
   FunnySkitJob j=jobs.create(s.language);
   j.setMode(FunnySkitJob.Mode.ADVENTURE); j.setSeriesId(s.id); j.setStyle(s.style); j.setIdea(s.premise); j.setTone("cinematic wonder"); j.setDirection(direction);
   j.setCharacterId(s.characterId); j.setCharacterReferenceId(s.characterReferenceId); j.setCharacterReferencePath(s.characterReferencePath);
   var ep=new AdventureSeriesStore.Episode(); ep.number=s.episodes.size()+1; ep.jobId=j.getId().toString(); ep.status="WRITING";
   j.setEpisodeNumber(ep.number);
   s.episodes.add(ep); seriesStore.save(s);
   return j;   // the controller starts generateAsync through the Spring proxy (self-calls would not be async)
 }

 private com.fasterxml.jackson.databind.JsonNode writeEpisode(FunnySkitJob job) throws Exception {
   var s=seriesStore.get(job.getSeriesId());
   for(int i=0;i<60&&s.bibleJson==null;i++){ job.setStage("Writing the series bible"); Thread.sleep(2000); }   // the bible is written in the background
   StringBuilder digest=new StringBuilder(); String last=null;
   for(var e:s.episodes){ if(e.number>=job.getEpisodeNumber()||e.summary==null) continue; digest.append("Episode ").append(e.number).append(" - ").append(e.title).append(": ").append(e.summary).append('\n'); last=e.cliffhanger; }
   job.setStage("Writing episode "+job.getEpisodeNumber());
   var ep=objectMapper.readTree(gateway.llm().generateStructured(SkitWritersRoom.episodeSystemPrompt(),
       SkitWritersRoom.episodeUserPrompt(s.bibleJson==null?"{}":s.bibleJson, job.getEpisodeNumber(), digest.toString(), last, s.language, job.getDirection())));
   if(!s.narration) for(int i=1;i<=3;i++) ((com.fasterxml.jackson.databind.node.ObjectNode)ep).put("part"+i+"Dialogue","");
   return ep;
 }

 /** Keep the series file in step with the episode job. */
 private void updateEpisode(FunnySkitJob job, String status, String video, String lastFrame){
   if(job.getSeriesId()==null||seriesStore==null) return;
   try{
     var s=seriesStore.get(job.getSeriesId());
     for(var e:s.episodes) if(job.getId().toString().equals(e.jobId)){
       e.status=status; if(job.getTitle()!=null) e.title=job.getTitle(); if(job.getSummary()!=null) e.summary=job.getSummary(); if(job.getCliffhanger()!=null) e.cliffhanger=job.getCliffhanger();
       if(video!=null) e.videoPath=video; if(lastFrame!=null) e.lastFramePath=lastFrame;
     }
     seriesStore.save(s);
   }catch(Exception ignored){}
 }

 /** The first frame of a new episode = the last frame of the previous finished one (continuity across episodes). */
 private Path previousEpisodeFrame(FunnySkitJob job){
   if(job.getMode()!=FunnySkitJob.Mode.ADVENTURE||job.getSeriesId()==null) return null;
   try{
     var s=seriesStore.get(job.getSeriesId());
     for(int i=s.episodes.size()-1;i>=0;i--){ var e=s.episodes.get(i); if(e.number<job.getEpisodeNumber()&&e.lastFramePath!=null&&Files.exists(Path.of(e.lastFramePath))) return Path.of(e.lastFramePath); }
   }catch(Exception ignored){}
   return null;
 }

 public List<AdventureSeriesStore.Series> listSeries(){ return seriesStore.list(); }
 public AdventureSeriesStore.Series series(String id){ return seriesStore.get(id); }
 private static String quote(String v){ return "\""+(v==null?"":v.replace("\\","\\\\").replace("\"","\\\""))+"\""; }

 /** Prompt for one storyboard frame (comedy or adventure). */
 private String framePrompt(FunnySkitJob job, int i, String visual){
   boolean adv=job.getMode()==FunnySkitJob.Mode.ADVENTURE;
   String continuity;
   if(i==0) continuity=adv&&job.getEpisodeNumber()>1
       ? "Opening frame of this episode: continue EXACTLY from the reference end frame of the previous episode (same place, light and pose), then this shot's action."
       : "Opening frame. Establish the situation clearly.";
   else continuity="Continuation frame. Continue directly from the previous storyboard image; preserve the same character identity, wardrobe, location, props, lighting and camera context. Do not reset the scene.";
   String head=adv ? "Vertical 9:16 cinematic adventure film frame, breathtaking composition, the hero clearly readable. "
                   : "Vertical 9:16 viral comedy storyboard frame. ";
   String acting=adv ? "Expressive wonder, awe and curiosity on the hero's face; epic scale and depth." : "Expressive comedic acting, exaggerated readable reaction, clear composition.";
   return head+visual+" "+continuity+" The uploaded character is locked and must remain visually identical (same face, hair, outfit, age, props), redrawn in this style: "+styleText(job)+". "+acting+" No text, no subtitles.";
 }

 private void generateStoryboardImages(FunnySkitJob job, Path character, String[] visuals, List<Path> temp) throws Exception {
   String[] stored=new String[3];
   Path episodeStart=previousEpisodeFrame(job);
   Path previous=character;
   for(int i=0;i<3;i++){
     Path cont=i==0?episodeStart:previous;
     var req=new com.aistorystudio.provider.ImageGenerationProvider.ImageGenerationRequest(
       framePrompt(job,i,visuals[i]),
       "deformed face, identity drift, extra limbs, duplicate character, text, subtitles, watermark, logo",768,1344,30,0,null,"qwen-image-2-1-16gb-ref",null,character.toString(),cont==null?null:cont.toString());
     var result=gateway.generateImage(req);
     Path img=storage.store("funny-skits/jobs/"+job.getId()+"/scene-"+(i+1)+".png",result.imageBytes());
     stored[i]=img.toString(); previous=img;
   }
   job.setImagePaths(stored);
 }

 public void regenerateImage(UUID id, int scene) { regenerateImage(id, scene, false); }

 /** Redraw one part. cascade=true also redraws the following parts so they continue from the new frame. */
 public void regenerateImage(UUID id, int scene, boolean cascade) {
   FunnySkitJob job=jobs.get(id); if(job==null) throw new IllegalArgumentException("Skit job not found");
   if(scene<1||scene>3) throw new IllegalArgumentException("Scene must be 1, 2 or 3.");
   try{
     Path character=findCharacterReference(id);
     for(int s=scene; s<=(cascade?3:scene); s++){
       String[] paths=job.getImagePaths().clone();
       Path previous=s==1?previousEpisodeFrame(job):(paths[s-2]==null?character:Path.of(paths[s-2]));
       String visual=job.getVisualPrompts()[s-1];
       var req=new com.aistorystudio.provider.ImageGenerationProvider.ImageGenerationRequest(framePrompt(job,s-1,visual),"deformed face, identity drift, extra limbs, duplicate character, text, subtitles, watermark",768,1344,30,0,null,"qwen-image-2-1-16gb-ref",null,character.toString(),previous==null?null:previous.toString());
       var result=gateway.generateImage(req);
       Path img=storage.store("funny-skits/jobs/"+id+"/scene-"+s+"-regen-"+System.currentTimeMillis()+".png",result.imageBytes());
       paths[s-1]=img.toString(); job.setImagePaths(paths);
     }
   }catch(Exception e){throw new IllegalStateException("Could not regenerate scene "+scene+": "+e.getMessage(),e);}
 }

 /** Edit one part's picture description and/or dialogue (then redraw / re-render as the user wants). */
 public void updatePart(UUID id, int scene, String visual, String dialogue){
   FunnySkitJob job=jobs.get(id); if(job==null) throw new IllegalArgumentException("Skit job not found");
   if(scene<1||scene>3) throw new IllegalArgumentException("Scene must be 1, 2 or 3.");
   String[] v=job.getVisualPrompts().clone(), d=job.getDialogues().clone();
   if(visual!=null&&!visual.isBlank()) v[scene-1]=visual.trim();
   if(dialogue!=null&&!dialogue.isBlank()) d[scene-1]=dialogue.trim();
   job.setVisualPrompts(v); job.setDialogues(d); job.setScript(d[0]+" | "+d[1]+" | "+d[2]);
   if(job.getResultVideoPath()!=null) job.setResultVideoPath(null); // the old video no longer matches
 }

 /** Write a completely new script (same idea, character, style) and new storyboard frames. */
 public void rewriteScript(UUID id, String speaker){
   FunnySkitJob job=jobs.get(id); if(job==null) throw new IllegalArgumentException("Skit job not found");
   if(job.getIdea()==null) throw new IllegalStateException("This skit was created before rewriting was possible. Start a new skit.");
   job.setResultVideoPath(null); job.setErrorMessage(null);
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
   if(job.getMode()==FunnySkitJob.Mode.ADVENTURE) updateEpisode(job,"RENDERING",null,null);
   Path work=null;
   try{
     Path character=findCharacterReference(id);
     work=Files.createTempDirectory("skit-h3-"+id+"-");
     H3SceneRenderer.SpeechEngine engine=H3SceneRenderer.SpeechEngine.parse(speechEngine);
     List<Path> parts=new ArrayList<>();
     for(int i=0;i<3;i++){
       Path storyboard=Path.of(job.getImagePaths()[i]);
       boolean adv=job.getMode()==FunnySkitJob.Mode.ADVENTURE;
       job.setStage("Filming part "+(i+1)+" of 3");
       List<H3SceneRenderer.Line> lines=adv?narrationLines(job.getDialogues()[i]):skitLines(job.getDialogues()[i], tone);
       String visual=job.getVisualPrompts()[i]+(adv
           ? ". Shot "+(i+1)+" of 3 of a cinematic adventure episode; slow, smooth, motivated camera move; wonder and scale; no text."
           : ". Part "+(i+1)+" of 3 of a short comedy skit; expressive comedic acting and timing"+(i==2&&job.getButton()!=null?"; end on the silent reaction: "+job.getButton():"")+".");
       String sound=job.getSoundscape()==null||job.getSoundscape().isBlank()?null:(adv?"Music and ambience: ":"Ambience: ")+job.getSoundscape();
       var spec=new H3SceneRenderer.SceneSpec(visual,null,adv?"wonder":tone,styleText(job),job.getLanguage(),
           lines,null,sound,5.0,(adv?"series-"+job.getSeriesId():"skit-"+id),engine);
       var r=h3SceneRenderer.render(spec,new H3SceneRenderer.Options(storyboard,0,0,work.resolve("part-"+(i+1)),()->false,character));
       Path part=work.resolve("skit-part-"+(i+1)+".mp4"); Files.copy(r.video(),part,java.nio.file.StandardCopyOption.REPLACE_EXISTING); parts.add(part);
     }
     Path out=work.resolve("funny-skit.mp4");
     // H3 renders 480x832; deliver the skit as a 1080x1920 reel (lanczos + light sharpening in the merge)
     int[] wh=firstSize(parts.get(0));
     boolean vertical=wh[1]>=wh[0];
     Path merged=work.resolve("funny-skit-merged.mp4");
     ClipMerger.merge(parts,merged,vertical?1080:1920,vertical?1920:1080,0);
     Process sharpen=new ProcessBuilder("ffmpeg","-nostdin","-y","-i",merged.toString(),"-vf","unsharp=5:5:0.55:5:5:0.0","-c:v","libx264","-preset","medium","-crf","18","-pix_fmt","yuv420p","-c:a","copy","-movflags","+faststart",out.toString()).redirectErrorStream(true).start();
     sharpen.getInputStream().readAllBytes();
     if(sharpen.waitFor()!=0) Files.copy(merged,out,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
     Path stored=storage.store("funny-skits/results/"+id+".mp4",Files.readAllBytes(out));
     job.setResultVideoPath(stored.toString()); job.setStage(null);
     if(job.getMode()==FunnySkitJob.Mode.ADVENTURE){
       // the last frame becomes the first frame of the next episode
       String frame=null;
       try{ Path f=work.resolve("last-frame.png"); extractLastFrame(out,f); frame=storage.store("adventure-series/"+job.getSeriesId()+"/ep-"+job.getEpisodeNumber()+"-last.png",Files.readAllBytes(f)).toString(); }catch(Exception ignored){}
       updateEpisode(job,"DONE",stored.toString(),frame);
     }
     job.setStatus(FunnySkitJob.Status.SUCCEEDED);
   }catch(Exception e){job.setErrorMessage(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());job.setStatus(FunnySkitJob.Status.FAILED);
     if(job.getMode()==FunnySkitJob.Mode.ADVENTURE) updateEpisode(job,"FAILED",null,null);}
   finally{ if(work!=null) deleteTree(work); }
 }

 /** Adventure voice-over: one narrator line per shot (never lip-synced); empty = music only. */
 private List<H3SceneRenderer.Line> narrationLines(String text){
   if(text==null||text.isBlank()) return List.of();
   return List.of(new H3SceneRenderer.Line("Narrator",text.trim(),"hushed, awe-struck storybook trailer narration",true,0,null));
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
   Process p=new ProcessBuilder("ffmpeg","-y","-sseof","-0.08","-i",video.toString(),"-frames:v","1","-update","1",out.toString()).redirectErrorStream(true).start();
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
 public FunnySkitJob job(UUID id){var j=jobs.get(id);if(j==null)throw new IllegalArgumentException("Skit job not found");return j;}
 public JobView status(UUID id){var j=jobs.get(id); if(j==null)throw new IllegalArgumentException("Skit job not found"); String[] urls=new String[3]; String[] p=j.getImagePaths(); for(int i=0;i<3;i++) urls[i]=(p!=null&&p[i]!=null)?"/api/funny-skits/jobs/"+id+"/images/"+(i+1):null; return new JobView(j.getId(),j.getStatus(),j.getErrorMessage(),j.getLanguage(),j.getScript(),j.getVisualPrompts(),j.getDialogues(),urls,j.getSoundscape(),j.getResultVideoPath(),j.getCharacterId(),j.getCharacterReferenceId(),
     j.getMode().name(),j.getTitle(),j.getEngine(),j.getButton(),j.getSeriesId(),j.getEpisodeNumber(),j.getSummary(),j.getCliffhanger(),j.getStage());}
 public Path image(UUID id,int scene){var j=jobs.get(id);if(j==null||scene<1||scene>3||j.getImagePaths()[scene-1]==null)throw new IllegalStateException("Storyboard image is not ready.");return Path.of(j.getImagePaths()[scene-1]);}
 public Path result(UUID id){var j=jobs.get(id);if(j==null||j.getStatus()!=FunnySkitJob.Status.SUCCEEDED||j.getResultVideoPath()==null)throw new IllegalStateException("Skit is not ready yet.");return Path.of(j.getResultVideoPath());}
}
