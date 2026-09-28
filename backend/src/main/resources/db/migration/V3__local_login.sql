ALTER TABLE app_user ADD COLUMN google_subject VARCHAR(100);
UPDATE app_user SET google_subject=id WHERE demo=FALSE AND refresh_token IS NOT NULL;
CREATE UNIQUE INDEX app_user_google_subject_idx ON app_user(google_subject);

CREATE TABLE local_login (
  id VARCHAR(20) PRIMARY KEY,
  user_id VARCHAR(100) NOT NULL REFERENCES app_user(id),
  username VARCHAR(64) NOT NULL,
  credential_revision VARCHAR(64) NOT NULL
);

CREATE TABLE remembered_login (
  token_hash VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(100) NOT NULL REFERENCES app_user(id),
  credential_revision VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL
);
CREATE INDEX remembered_login_user_idx ON remembered_login(user_id);
CREATE INDEX remembered_login_expiry_idx ON remembered_login(expires_at);
