CREATE TABLE activity_record (
  id VARCHAR(40) PRIMARY KEY,
  user_id VARCHAR(100) NOT NULL REFERENCES app_user(id),
  event_id VARCHAR(40) REFERENCES calendar_event(id) ON DELETE SET NULL,
  event_title VARCHAR(1000),
  payload TEXT NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX activity_user_idx ON activity_record(user_id);
