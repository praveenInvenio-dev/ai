-- A locked shot survives AI Edit regeneration unchanged: the planner keeps it
-- in place and only replans the rest of the timeline around it. Without this,
-- "regenerate" always meant "discard every manual choice", which is why
-- someone approving one shot and asking for a re-edit had no way to keep it.
ALTER TABLE timeline_clip ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
