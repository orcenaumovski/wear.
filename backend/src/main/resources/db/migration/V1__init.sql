CREATE TABLE IF NOT EXISTS item (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT,
  category TEXT,
  tags_json TEXT,
  colors_json TEXT,
  image_path TEXT NOT NULL,
  created_at_epoch_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS outfit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT,
  reasoning TEXT,
  created_at_epoch_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS outfit_item (
  outfit_id INTEGER NOT NULL,
  item_id INTEGER NOT NULL,
  role TEXT NOT NULL,
  PRIMARY KEY (outfit_id, item_id, role),
  FOREIGN KEY (outfit_id) REFERENCES outfit(id) ON DELETE CASCADE,
  FOREIGN KEY (item_id) REFERENCES item(id) ON DELETE CASCADE
);

