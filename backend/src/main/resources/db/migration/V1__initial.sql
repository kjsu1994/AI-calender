CREATE TABLE app_user (
  id VARCHAR(100) PRIMARY KEY,
  email VARCHAR(320) NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  refresh_token TEXT,
  calendar_id VARCHAR(1024),
  calendar_name VARCHAR(300),
  sync_token TEXT,
  sync_status VARCHAR(40) NOT NULL DEFAULT 'DISCONNECTED',
  last_sync VARCHAR(40),
  demo BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE calendar_event (
  id VARCHAR(40) PRIMARY KEY,
  user_id VARCHAR(100) NOT NULL REFERENCES app_user(id),
  google_id VARCHAR(1024) NOT NULL,
  etag VARCHAR(300),
  payload TEXT NOT NULL,
  remote_payload TEXT,
  sync_state VARCHAR(30) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  recurring BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (user_id, google_id)
);
CREATE INDEX event_user_idx ON calendar_event(user_id);
CREATE TABLE outbox (
  event_id VARCHAR(40) PRIMARY KEY REFERENCES calendar_event(id),
  operation VARCHAR(12) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE voice_command (
  id VARCHAR(40) PRIMARY KEY,
  user_id VARCHAR(100) NOT NULL REFERENCES app_user(id),
  request_id VARCHAR(80) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  plan TEXT NOT NULL,
  result TEXT NOT NULL,
  expires_at BIGINT NOT NULL,
  UNIQUE (user_id, request_id)
);
