-- V9: Optional system prompt for dispatched tasks
-- Allows the dispatcher to inject context (identity, persona, constraints)
-- into the LLM call without embedding it in the user-facing prompt.
-- For claude CLI: passed as --system-prompt.
-- For OpenAI-compatible APIs: prepended as a {"role":"system"} message.

ALTER TABLE pending_tasks ADD COLUMN system_prompt TEXT;
