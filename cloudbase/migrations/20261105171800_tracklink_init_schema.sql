-- TrackLink 云开发（CloudBase PostgreSQL）初始表结构
-- 表统一 tl_ 前缀，避免与同环境其它项目冲突

create table if not exists public.tl_meta (
  k text primary key,
  v text not null
);

create table if not exists public.tl_users (
  id bigserial primary key,
  username text not null unique,
  pass text not null,
  role text not null check (role in ('admin', 'viewer')),
  created_at bigint not null
);

create table if not exists public.tl_devices (
  id text primary key,
  name text not null,
  model text,
  note text,
  created_at bigint not null,
  last_seen bigint,
  battery integer,
  last_lat double precision,
  last_lon double precision,
  last_ts bigint,
  last_acc double precision
);

create table if not exists public.tl_points (
  id bigserial primary key,
  device_id text not null references public.tl_devices(id) on delete cascade,
  ts bigint not null,
  lat double precision not null,
  lon double precision not null,
  acc double precision,
  spd double precision,
  brg double precision,
  alt double precision,
  bat integer
);

create unique index if not exists ux_tl_points_device_ts on public.tl_points(device_id, ts);
create index if not exists ix_tl_points_ts on public.tl_points(ts);

create table if not exists public.tl_acl (
  user_id bigint not null references public.tl_users(id) on delete cascade,
  device_id text not null references public.tl_devices(id) on delete cascade,
  primary key (user_id, device_id)
);

-- 批量上报：一次调用内完成 设备 upsert + 点位去重插入 + 设备缓存更新（原子）
create or replace function public.tl_ingest_points(
  p_device_id text,
  p_name text,
  p_model text,
  p_battery integer,
  p_now bigint,
  p_points jsonb
) returns jsonb
language plpgsql
security definer
as $$
declare
  v_inserted integer := 0;
  v_ts bigint;
  v_lat double precision;
  v_lon double precision;
  v_acc double precision;
begin
  insert into public.tl_devices (id, name, model, created_at)
  values (p_device_id, coalesce(nullif(p_name, ''), p_device_id), nullif(p_model, ''), p_now)
  on conflict (id) do nothing;

  with src as (
    select *
    from jsonb_to_recordset(coalesce(p_points, '[]'::jsonb)) as x(
      ts bigint, lat double precision, lon double precision,
      acc double precision, spd double precision, brg double precision,
      alt double precision, bat integer
    )
  ), ins as (
    insert into public.tl_points (device_id, ts, lat, lon, acc, spd, brg, alt, bat)
    select p_device_id, ts, lat, lon, acc, spd, brg, alt, coalesce(bat, p_battery)
    from src
    on conflict (device_id, ts) do nothing
    returning 1
  )
  select count(*) into v_inserted from ins;

  select ts, lat, lon, acc
    into v_ts, v_lat, v_lon, v_acc
  from jsonb_to_recordset(coalesce(p_points, '[]'::jsonb)) as x(
    ts bigint, lat double precision, lon double precision, acc double precision
  )
  order by ts desc
  limit 1;

  update public.tl_devices set
    last_seen = p_now,
    battery = coalesce(p_battery, battery),
    last_lat = coalesce(v_lat, last_lat),
    last_lon = coalesce(v_lon, last_lon),
    last_ts = coalesce(v_ts, last_ts),
    last_acc = coalesce(v_acc, last_acc),
    name = case when nullif(p_name, '') is not null then p_name else name end
  where id = p_device_id;

  return jsonb_build_object('inserted', v_inserted);
end;
$$;

-- 服务端角色授权（PostgREST 走 service_role）
grant usage on schema public to service_role;
grant select, insert, update, delete on public.tl_meta, public.tl_users, public.tl_devices, public.tl_points, public.tl_acl to service_role;
grant execute on function public.tl_ingest_points(text, text, text, integer, bigint, jsonb) to service_role;
