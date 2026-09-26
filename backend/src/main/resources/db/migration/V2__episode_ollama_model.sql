-- Lets each episode remember which locally-installed Ollama model it was
-- drafted with, instead of the app being pinned to one configured model.
ALTER TABLE episode ADD COLUMN ollama_model VARCHAR(128);
