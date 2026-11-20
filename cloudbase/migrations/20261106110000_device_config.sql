-- 设备采样/省电配置：App2 下发，App1 心跳/批量上报时拉取
-- 字段边界与默认值必须与 router.js 的 CONFIG_FIELDS 保持一致

create table if not exists public.tl_device_config (
  device_id text primary key references public.tl_devices(id) on delete cascade,
  sample_interval_s integer not null default 15,
  min_distance_m integer not null default 10,
  accuracy_max_m integer not null default 50,
  sleep_after_min integer not null default 3,
  batch_seconds integer not null default 60,
  heartbeat_s integer not null default 300,
  updated_at bigint not null
);
