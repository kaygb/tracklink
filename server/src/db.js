import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';

const SCHEMA = `
CREATE TABLE IF NOT EXISTS meta (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  pass TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('admin','viewer')),
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  model TEXT,
  note TEXT,
  created_at INTEGER NOT NULL,
  last_seen INTEGER,
  battery INTEGER,
  last_lat REAL,
  last_lon REAL,
  last_ts INTEGER,
  last_acc REAL
);

CREATE TABLE IF NOT EXISTS points (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  ts INTEGER NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  acc REAL,
  spd REAL,
  brg REAL,
  alt REAL,
  bat INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_points_device_ts ON points(device_id, ts);
CREATE INDEX IF NOT EXISTS ix_points_ts ON points(ts);

CREATE TABLE IF NOT EXISTS acl (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, device_id)
);

-- 设备采样/省电配置：App2 下发，App1 拉取（边界与默认值与 routes.js 的 CONFIG_FIELDS 一致）
CREATE TABLE IF NOT EXISTS device_config (
  device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  sample_interval_s INTEGER NOT NULL DEFAULT 15,
  min_distance_m INTEGER NOT NULL DEFAULT 10,
  accuracy_max_m INTEGER NOT NULL DEFAULT 50,
  sleep_after_min INTEGER NOT NULL DEFAULT 3,
  batch_seconds INTEGER NOT NULL DEFAULT 60,
  heartbeat_s INTEGER NOT NULL DEFAULT 300,
  updated_at INTEGER NOT NULL
);
`;

export function initDb(file) {
  fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec('PRAGMA journal_mode = WAL');
  db.exec('PRAGMA foreign_keys = ON');
  db.exec('PRAGMA busy_timeout = 5000');
  db.exec(SCHEMA);
  return db;
}

export function getMeta(db, k) {
  const row = db.prepare('SELECT v FROM meta WHERE k = ?').get(k);
  return row ? row.v : null;
}

export function setMeta(db, k, v) {
  db.prepare(
    'INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v',
  ).run(k, String(v));
}

export function purgeOldPoints(db, retentionDays) {
  if (!Number.isFinite(retentionDays) || retentionDays <= 0) return 0;
  const cutoff = Date.now() - retentionDays * 86400_000;
  const r = db.prepare('DELETE FROM points WHERE ts < ?').run(cutoff);
  return Number(r.changes ?? 0);
}
