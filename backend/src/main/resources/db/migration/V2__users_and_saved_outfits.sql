CREATE TABLE IF NOT EXISTS app_user (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  created_at_epoch_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_session (
  token_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  created_at_epoch_ms INTEGER NOT NULL,
  expires_at_epoch_ms INTEGER NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

ALTER TABLE item ADD COLUMN user_id INTEGER;
ALTER TABLE outfit ADD COLUMN user_id INTEGER;

CREATE INDEX IF NOT EXISTS idx_item_user_id ON item(user_id);
CREATE INDEX IF NOT EXISTS idx_outfit_user_id ON outfit(user_id);
CREATE INDEX IF NOT EXISTS idx_auth_session_user_id ON auth_session(user_id);
CREATE INDEX IF NOT EXISTS idx_auth_session_expires_at ON auth_session(expires_at_epoch_ms);
