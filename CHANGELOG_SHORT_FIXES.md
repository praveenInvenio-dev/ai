# Fixes from reviewing the two uploaded shorts (43 s and 49 s)

- Length: shorts now target 155-175 words (60 s). If the model's draft is short, it is sent back with its word
  count and asked to explain more (not pad), up to twice; the longest valid draft wins. Warning if still short.
- Completeness rule in the prompt: what it is -> how it works on the example -> why it matters (before/after)
  -> its name -> one real-world use. Every technical word explained when it appears.
- Rich frames: each teaching scene must build one 3-6 element diagram that shows the mechanism, full width;
  no decorative chips/tabs/plots or unexplained numbers. Reels also zoom IN (up to 1.6x) on small diagrams.
- Overlap: the old heading now fades out before the new one fades in (they used to cross on top of each other).
- Outro: "Follow the page to learn one concept a day." (word "Follow" in green), also forced onto the model's outro.
- Voices: "Arjun"/"Maya" were US Chatterbox voices with Indian names. New Indian-English expressive voices
  (Arjun / Meera) are cloned once from the Edge en-IN voices into Chatterbox (local afterwards); they fall back
  to the Edge en-IN voice if the clone is not ready. US voices renamed Ryan / Amy. Chatterbox is used only for
  English; Hindi/Kannada/... narration automatically uses the native Indian voice.
