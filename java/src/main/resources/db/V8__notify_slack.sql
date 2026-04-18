-- V8: Slack notification field on pending tasks
-- Optional Slack user/channel ID to notify when a task completes or fails.
-- Populated at enqueue time; checked at completion time by the hub.

ALTER TABLE pending_tasks ADD COLUMN notify_slack TEXT;
