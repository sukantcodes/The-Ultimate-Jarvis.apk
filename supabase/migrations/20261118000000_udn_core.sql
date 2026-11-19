-- JARVIS Universal Device Network (UDN): core schema.
--
-- Design notes
--  * Existing tables were inspected first. public.app_devices is owner-dashboard telemetry (CHECKed event types,
--    owner-only RLS) and is intentionally NOT extended; UDN devices need per-device public keys, roles and
--    command routing, so they live in their own udn_* tables.
--  * Every udn_* table has RLS enabled with NO client policies, except udn_wakeups (ids only, SELECT for the
--    owner so Supabase Realtime can wake devices). All reads and writes go through the udn-api Edge Function,
--    which authenticates the user JWT AND a per-device ECDSA signature and then uses the service role.
--  * Security-critical state changes (parent claim, pairing, revoke, command claim/result) are SECURITY DEFINER
--    functions executable only by service_role, so they are atomic and cannot be called with a user token.

create table if not exists public.udn_devices (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  platform text not null check (platform in ('android', 'windows')),
  name text not null check (char_length(name) between 1 and 60),
  manufacturer text check (char_length(manufacturer) <= 60),
  model text check (char_length(model) <= 80),
  os_version text check (char_length(os_version) <= 40),
  app_version text check (char_length(app_version) <= 40),
  public_key text not null check (char_length(public_key) between 100 and 200),
  key_fingerprint text not null check (key_fingerprint ~ '^[A-Fa-f0-9]{64}$'),
  status text not null default 'pending' check (status in ('pending', 'authorized', 'revoked')),
  battery_pct smallint check (battery_pct between 0 and 100),
  battery_charging boolean,
  connection_type text check (connection_type in ('wifi', 'cellular', 'ethernet', 'other', 'unknown')),
  lan_host text check (char_length(lan_host) <= 64),
  lan_port integer check (lan_port between 1024 and 65535),
  lan_updated_at timestamptz,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  authorized_at timestamptz,
  authorized_by uuid references public.udn_devices(id) on delete set null,
  revoked_at timestamptz,
  unique (owner_id, key_fingerprint)
);
create index if not exists udn_devices_owner_idx on public.udn_devices (owner_id, status);

create table if not exists public.udn_parent_assignments (
  owner_id uuid not null references auth.users(id) on delete cascade,
  slot smallint not null check (slot in (1, 2)),
  device_id uuid not null unique references public.udn_devices(id) on delete cascade,
  assigned_at timestamptz not null default now(),
  assigned_via text not null check (assigned_via in ('first_claim', 'pairing_code')),
  primary key (owner_id, slot)
);

create table if not exists public.udn_device_capabilities (
  device_id uuid not null references public.udn_devices(id) on delete cascade,
  capability text not null check (capability ~ '^[a-z]+(\.[a-z_]+)+$'),
  available boolean not null,
  reason text check (char_length(reason) <= 200),
  os_permission text check (char_length(os_permission) <= 80),
  permission_granted boolean,
  policy text not null default 'default' check (policy in ('default', 'allow', 'confirm', 'deny')),
  updated_at timestamptz not null default now(),
  primary key (device_id, capability)
);

create table if not exists public.udn_device_permissions (
  device_id uuid not null references public.udn_devices(id) on delete cascade,
  permission text not null check (char_length(permission) between 1 and 80),
  granted boolean not null,
  updated_at timestamptz not null default now(),
  primary key (device_id, permission)
);

create table if not exists public.udn_pairing_requests (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('parent', 'child')),
  code_hash text not null check (code_hash ~ '^[a-f0-9]{64}$'),
  created_by uuid not null references public.udn_devices(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  consumed_at timestamptz,
  consumed_by uuid references public.udn_devices(id) on delete set null
);
create index if not exists udn_pairing_lookup_idx on public.udn_pairing_requests (owner_id, code_hash) where consumed_at is null;

create table if not exists public.udn_command_queue (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  source_device_id uuid not null references public.udn_devices(id) on delete cascade,
  target_device_id uuid not null references public.udn_devices(id) on delete cascade,
  capability text not null check (capability ~ '^[a-z]+(\.[a-z_]+)+$'),
  params jsonb not null default '{}'::jsonb check (octet_length(params::text) <= 8192),
  nonce text not null check (char_length(nonce) between 16 and 64),
  sensitivity text not null check (sensitivity in ('low', 'medium', 'high')),
  requires_consent boolean not null default false,
  status text not null default 'queued' check (status in
    ('queued', 'delivered', 'running', 'succeeded', 'failed', 'rejected', 'expired', 'cancelled', 'timed_out')),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  delivered_at timestamptz,
  acked_at timestamptz,
  completed_at timestamptz,
  delivery_count integer not null default 0,
  result jsonb check (octet_length(result::text) <= 16384),
  error_code text check (char_length(error_code) <= 60),
  error_message text check (char_length(error_message) <= 300),
  unique (source_device_id, nonce),
  check (expires_at > created_at and expires_at <= created_at + interval '10 minutes')
);
create index if not exists udn_command_target_idx on public.udn_command_queue (target_device_id, status, created_at);
create index if not exists udn_command_owner_idx on public.udn_command_queue (owner_id, created_at desc);

create table if not exists public.udn_command_events (
  id bigint generated always as identity primary key,
  command_id uuid not null references public.udn_command_queue(id) on delete cascade,
  device_id uuid references public.udn_devices(id) on delete set null,
  event text not null check (char_length(event) <= 40),
  detail jsonb,
  created_at timestamptz not null default now()
);
create index if not exists udn_command_events_cmd_idx on public.udn_command_events (command_id, id);

create table if not exists public.udn_device_sessions (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.udn_devices(id) on delete cascade,
  started_at timestamptz not null default now(),
  last_heartbeat_at timestamptz not null default now(),
  ended_at timestamptz,
  connection_type text check (connection_type in ('wifi', 'cellular', 'ethernet', 'other', 'unknown'))
);
create index if not exists udn_sessions_device_idx on public.udn_device_sessions (device_id, started_at desc);

create table if not exists public.udn_device_groups (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 40),
  created_at timestamptz not null default now(),
  unique (owner_id, name)
);

create table if not exists public.udn_device_group_members (
  group_id uuid not null references public.udn_device_groups(id) on delete cascade,
  device_id uuid not null references public.udn_devices(id) on delete cascade,
  primary key (group_id, device_id)
);

create table if not exists public.udn_audit_logs (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete cascade,
  actor_device_id uuid references public.udn_devices(id) on delete set null,
  action text not null check (char_length(action) <= 60),
  target_device_id uuid references public.udn_devices(id) on delete set null,
  detail jsonb,
  created_at timestamptz not null default now()
);
create index if not exists udn_audit_owner_idx on public.udn_audit_logs (owner_id, id desc);

-- Replay protection for signed requests, and a small fixed-window rate limiter.
create table if not exists public.udn_request_nonces (
  device_id uuid not null references public.udn_devices(id) on delete cascade,
  nonce text not null,
  seen_at timestamptz not null default now(),
  primary key (device_id, nonce)
);
create index if not exists udn_request_nonces_seen_idx on public.udn_request_nonces (seen_at);

create table if not exists public.udn_rate_limits (
  key text not null,
  window_start timestamptz not null,
  count integer not null default 0,
  primary key (key, window_start)
);

-- Wake-up signals only (ids, never command contents). This is the one table clients may SELECT, so Supabase
-- Realtime can tell a device "poll now" without exposing command parameters.
create table if not exists public.udn_wakeups (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('command', 'registry')),
  target_device_id uuid references public.udn_devices(id) on delete cascade,
  command_id uuid,
  created_at timestamptz not null default now()
);
create index if not exists udn_wakeups_owner_idx on public.udn_wakeups (owner_id, id desc);

-- ---------------------------------------------------------------- RLS
alter table public.udn_devices enable row level security;
alter table public.udn_parent_assignments enable row level security;
alter table public.udn_device_capabilities enable row level security;
alter table public.udn_device_permissions enable row level security;
alter table public.udn_pairing_requests enable row level security;
alter table public.udn_command_queue enable row level security;
alter table public.udn_command_events enable row level security;
alter table public.udn_device_sessions enable row level security;
alter table public.udn_device_groups enable row level security;
alter table public.udn_device_group_members enable row level security;
alter table public.udn_audit_logs enable row level security;
alter table public.udn_request_nonces enable row level security;
alter table public.udn_rate_limits enable row level security;
alter table public.udn_wakeups enable row level security;

revoke all on public.udn_devices, public.udn_parent_assignments, public.udn_device_capabilities,
  public.udn_device_permissions, public.udn_pairing_requests, public.udn_command_queue,
  public.udn_command_events, public.udn_device_sessions, public.udn_device_groups,
  public.udn_device_group_members, public.udn_audit_logs, public.udn_request_nonces,
  public.udn_rate_limits, public.udn_wakeups from anon, authenticated;

grant select on public.udn_wakeups to authenticated;
drop policy if exists udn_wakeups_owner_select on public.udn_wakeups;
create policy udn_wakeups_owner_select on public.udn_wakeups
  for select to authenticated using (owner_id = (select auth.uid()));

do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
     and not exists (select 1 from pg_publication_tables
                     where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'udn_wakeups') then
    alter publication supabase_realtime add table public.udn_wakeups;
  end if;
end $$;

-- ---------------------------------------------------------------- triggers
create or replace function public.udn_wake_on_command() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.udn_wakeups (owner_id, kind, target_device_id, command_id)
  values (new.owner_id, 'command', new.target_device_id, new.id);
  insert into public.udn_command_events (command_id, device_id, event)
  values (new.id, new.source_device_id, 'queued');
  return new;
end $$;
drop trigger if exists udn_command_wake on public.udn_command_queue;
create trigger udn_command_wake after insert on public.udn_command_queue
  for each row execute function public.udn_wake_on_command();

create or replace function public.udn_wake_on_registry() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' or new.status is distinct from old.status or new.name is distinct from old.name then
    insert into public.udn_wakeups (owner_id, kind) values (new.owner_id, 'registry');
  end if;
  return new;
end $$;
drop trigger if exists udn_registry_wake on public.udn_devices;
create trigger udn_registry_wake after insert or update on public.udn_devices
  for each row execute function public.udn_wake_on_registry();

-- A parent assignment may only point at an authorized-or-pending device owned by the same account.
create or replace function public.udn_guard_parent_assignment() returns trigger
language plpgsql set search_path = public as $$
declare d public.udn_devices;
begin
  select * into d from public.udn_devices where id = new.device_id;
  if d.id is null or d.owner_id <> new.owner_id or d.status = 'revoked' then
    raise exception 'invalid parent assignment';
  end if;
  return new;
end $$;
drop trigger if exists udn_parent_guard on public.udn_parent_assignments;
create trigger udn_parent_guard before insert on public.udn_parent_assignments
  for each row execute function public.udn_guard_parent_assignment();

-- ---------------------------------------------------------------- functions (service_role only)
create or replace function public.udn_audit(p_owner uuid, p_actor uuid, p_action text, p_target uuid, p_detail jsonb default null)
returns void language sql security definer set search_path = public as $$
  insert into public.udn_audit_logs (owner_id, actor_device_id, action, target_device_id, detail)
  values (p_owner, p_actor, p_action, p_target, p_detail);
$$;

create or replace function public.udn_rate_check(p_key text, p_max integer, p_window_seconds integer)
returns boolean language plpgsql security definer set search_path = public as $$
declare w timestamptz; c integer;
begin
  w := to_timestamp(floor(extract(epoch from now()) / p_window_seconds) * p_window_seconds);
  insert into public.udn_rate_limits (key, window_start, count) values (p_key, w, 1)
  on conflict (key, window_start) do update set count = public.udn_rate_limits.count + 1
  returning count into c;
  return c <= p_max;
end $$;

create or replace function public.udn_register_nonce(p_device uuid, p_nonce text)
returns boolean language plpgsql security definer set search_path = public as $$
declare n integer;
begin
  insert into public.udn_request_nonces (device_id, nonce) values (p_device, p_nonce) on conflict do nothing;
  get diagnostics n = row_count;
  return n = 1;
end $$;

create or replace function public.udn_prune() returns void
language sql security definer set search_path = public as $$
  delete from public.udn_request_nonces where seen_at < now() - interval '15 minutes';
  delete from public.udn_rate_limits where window_start < now() - interval '2 hours';
  delete from public.udn_wakeups where created_at < now() - interval '1 day';
  delete from public.udn_pairing_requests where expires_at < now() - interval '1 day';
  delete from public.udn_command_queue where completed_at < now() - interval '30 days';
  delete from public.udn_audit_logs where created_at < now() - interval '180 days';
$$;

create or replace function public.udn_claim_first_parent(p_owner uuid, p_device uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare d public.udn_devices;
begin
  perform pg_advisory_xact_lock(hashtextextended('udn:' || p_owner::text, 0));
  select * into d from public.udn_devices where id = p_device and owner_id = p_owner for update;
  if d.id is null or d.status <> 'pending' then
    return jsonb_build_object('ok', false, 'error', 'device_not_pending');
  end if;
  if exists (select 1 from public.udn_parent_assignments where owner_id = p_owner) then
    return jsonb_build_object('ok', false, 'error', 'parents_exist');
  end if;
  insert into public.udn_parent_assignments (owner_id, slot, device_id, assigned_via)
  values (p_owner, 1, p_device, 'first_claim');
  update public.udn_devices set status = 'authorized', authorized_at = now(), authorized_by = p_device where id = p_device;
  perform public.udn_audit(p_owner, p_device, 'parent_claimed', p_device, jsonb_build_object('slot', 1, 'via', 'first_claim'));
  return jsonb_build_object('ok', true, 'slot', 1);
end $$;

create or replace function public.udn_redeem_code(p_owner uuid, p_device uuid, p_code_hash text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare d public.udn_devices; r public.udn_pairing_requests; s smallint;
begin
  perform pg_advisory_xact_lock(hashtextextended('udn:' || p_owner::text, 0));
  select * into d from public.udn_devices where id = p_device and owner_id = p_owner for update;
  if d.id is null or d.status <> 'pending' then
    return jsonb_build_object('ok', false, 'error', 'device_not_pending');
  end if;
  select * into r from public.udn_pairing_requests
   where owner_id = p_owner and code_hash = p_code_hash and consumed_at is null and expires_at > now()
   order by created_at desc limit 1 for update;
  if r.id is null then
    return jsonb_build_object('ok', false, 'error', 'invalid_code');
  end if;
  if not exists (select 1 from public.udn_parent_assignments where owner_id = p_owner and device_id = r.created_by) then
    return jsonb_build_object('ok', false, 'error', 'issuer_not_parent');
  end if;
  if r.kind = 'parent' then
    select x into s from generate_series(1, 2) as x
     where not exists (select 1 from public.udn_parent_assignments where owner_id = p_owner and slot = x) limit 1;
    if s is null then
      return jsonb_build_object('ok', false, 'error', 'no_free_slot');
    end if;
    insert into public.udn_parent_assignments (owner_id, slot, device_id, assigned_via)
    values (p_owner, s, p_device, 'pairing_code');
  end if;
  update public.udn_devices set status = 'authorized', authorized_at = now(), authorized_by = r.created_by where id = p_device;
  update public.udn_pairing_requests set consumed_at = now(), consumed_by = p_device where id = r.id;
  perform public.udn_audit(p_owner, r.created_by, case when r.kind = 'parent' then 'parent_paired' else 'child_paired' end,
                           p_device, jsonb_build_object('slot', s));
  return jsonb_build_object('ok', true, 'kind', r.kind, 'slot', s);
end $$;

create or replace function public.udn_decide_device(p_owner uuid, p_actor uuid, p_target uuid, p_decision text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare d public.udn_devices;
begin
  perform pg_advisory_xact_lock(hashtextextended('udn:' || p_owner::text, 0));
  if not exists (select 1 from public.udn_parent_assignments where owner_id = p_owner and device_id = p_actor) then
    return jsonb_build_object('ok', false, 'error', 'not_parent');
  end if;
  select * into d from public.udn_devices where id = p_target and owner_id = p_owner for update;
  if d.id is null or d.status <> 'pending' then
    return jsonb_build_object('ok', false, 'error', 'device_not_pending');
  end if;
  if p_decision = 'authorize' then
    update public.udn_devices set status = 'authorized', authorized_at = now(), authorized_by = p_actor where id = p_target;
    perform public.udn_audit(p_owner, p_actor, 'device_authorized', p_target, null);
  else
    update public.udn_devices set status = 'revoked', revoked_at = now() where id = p_target;
    perform public.udn_audit(p_owner, p_actor, 'device_rejected', p_target, null);
  end if;
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.udn_revoke_device(p_owner uuid, p_actor uuid, p_target uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare d public.udn_devices; actor_is_parent boolean; target_is_parent boolean;
begin
  perform pg_advisory_xact_lock(hashtextextended('udn:' || p_owner::text, 0));
  select exists (select 1 from public.udn_parent_assignments where owner_id = p_owner and device_id = p_actor) into actor_is_parent;
  select exists (select 1 from public.udn_parent_assignments where owner_id = p_owner and device_id = p_target) into target_is_parent;
  if not actor_is_parent and p_actor <> p_target then
    return jsonb_build_object('ok', false, 'error', 'not_parent');
  end if;
  -- A parent can only step itself down; neither parent can unilaterally remove the other.
  if target_is_parent and p_actor <> p_target then
    return jsonb_build_object('ok', false, 'error', 'cannot_revoke_parent');
  end if;
  select * into d from public.udn_devices where id = p_target and owner_id = p_owner for update;
  if d.id is null then
    return jsonb_build_object('ok', false, 'error', 'not_found');
  end if;
  if d.status = 'revoked' then
    return jsonb_build_object('ok', true, 'already', true);
  end if;
  update public.udn_devices set status = 'revoked', revoked_at = now() where id = p_target;
  delete from public.udn_parent_assignments where owner_id = p_owner and device_id = p_target;
  update public.udn_command_queue set status = 'cancelled', completed_at = now(), error_code = 'device_revoked'
   where target_device_id = p_target and status in ('queued', 'delivered', 'running');
  update public.udn_device_sessions set ended_at = now() where device_id = p_target and ended_at is null;
  perform public.udn_audit(p_owner, p_actor, case when p_actor = p_target then 'device_left' else 'device_revoked' end, p_target, null);
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.udn_heartbeat(
  p_device uuid, p_battery integer, p_charging boolean, p_conn text, p_lan_host text, p_lan_port integer)
returns void language plpgsql security definer set search_path = public as $$
declare prev timestamptz; open_id uuid;
begin
  select last_seen_at into prev from public.udn_devices where id = p_device for update;
  update public.udn_devices set
    last_seen_at = now(),
    battery_pct = coalesce(p_battery, battery_pct),
    battery_charging = coalesce(p_charging, battery_charging),
    connection_type = coalesce(p_conn, connection_type),
    lan_host = p_lan_host, lan_port = p_lan_port, lan_updated_at = case when p_lan_host is null then null else now() end
  where id = p_device;
  select id into open_id from public.udn_device_sessions where device_id = p_device and ended_at is null
   order by started_at desc limit 1;
  -- A gap of more than three missed 45 s heartbeats ends the old session; one missed beat does not.
  if open_id is not null and prev is not null and now() - prev > interval '150 seconds' then
    update public.udn_device_sessions set ended_at = prev where id = open_id;
    open_id := null;
  end if;
  if open_id is null then
    insert into public.udn_device_sessions (device_id, connection_type) values (p_device, p_conn);
  else
    update public.udn_device_sessions set last_heartbeat_at = now(), connection_type = coalesce(p_conn, connection_type) where id = open_id;
  end if;
end $$;

create or replace function public.udn_expire_commands(p_owner uuid) returns integer
language plpgsql security definer set search_path = public as $$
declare n integer := 0; k integer;
begin
  with u as (
    update public.udn_command_queue set status = 'expired', completed_at = now(), error_code = 'expired'
     where owner_id = p_owner and status in ('queued', 'delivered') and acked_at is null and expires_at <= now()
    returning id)
  insert into public.udn_command_events (command_id, event) select id, 'expired' from u;
  get diagnostics k = row_count; n := n + k;
  with u as (
    update public.udn_command_queue set status = 'timed_out', completed_at = now(), error_code = 'no_result'
     where owner_id = p_owner and status in ('delivered', 'running') and acked_at is not null
       and expires_at + interval '30 seconds' <= now()
    returning id)
  insert into public.udn_command_events (command_id, event) select id, 'timed_out' from u;
  get diagnostics k = row_count; n := n + k;
  return n;
end $$;

create or replace function public.udn_claim_commands(p_device uuid, p_limit integer default 5)
returns setof public.udn_command_queue language plpgsql security definer set search_path = public as $$
begin
  return query
  with c as (
    select q0.id from public.udn_command_queue q0
     where q0.target_device_id = p_device and q0.expires_at > now()
       and (q0.status = 'queued'
            or (q0.status = 'delivered' and q0.acked_at is null and q0.delivery_count < 4
                and q0.delivered_at < now() - interval '20 seconds'))
     order by q0.created_at limit greatest(1, least(p_limit, 10)) for update skip locked),
  u as (
    update public.udn_command_queue q set status = 'delivered', delivered_at = now(), delivery_count = q.delivery_count + 1
      from c where q.id = c.id returning q.*)
  select * from u;
end $$;

create or replace function public.udn_report_result(
  p_device uuid, p_command uuid, p_status text, p_result jsonb, p_error_code text, p_error_message text)
returns text language plpgsql security definer set search_path = public as $$
declare q public.udn_command_queue;
begin
  select * into q from public.udn_command_queue where id = p_command and target_device_id = p_device for update;
  if q.id is null then return 'not_found'; end if;
  if q.status in ('succeeded', 'failed', 'rejected', 'expired', 'cancelled', 'timed_out') then
    return case when q.status = p_status then 'noop' else 'conflict' end;
  end if;
  if p_status = 'running' then
    update public.udn_command_queue set status = 'running', acked_at = coalesce(acked_at, now()) where id = p_command;
  else
    update public.udn_command_queue set status = p_status, completed_at = now(), acked_at = coalesce(acked_at, now()),
           result = p_result, error_code = p_error_code, error_message = left(p_error_message, 300)
     where id = p_command;
  end if;
  insert into public.udn_command_events (command_id, device_id, event, detail)
  values (p_command, p_device, p_status, case when p_error_code is null then null else jsonb_build_object('error', p_error_code) end);
  return 'ok';
end $$;

create or replace function public.udn_ack_command(p_device uuid, p_command uuid)
returns boolean language plpgsql security definer set search_path = public as $$
declare n integer;
begin
  update public.udn_command_queue set acked_at = coalesce(acked_at, now())
   where id = p_command and target_device_id = p_device and status in ('delivered', 'running');
  get diagnostics n = row_count;
  if n = 1 then
    insert into public.udn_command_events (command_id, device_id, event) values (p_command, p_device, 'acked');
  end if;
  return n = 1;
end $$;

revoke execute on function
  public.udn_audit(uuid, uuid, text, uuid, jsonb),
  public.udn_rate_check(text, integer, integer),
  public.udn_register_nonce(uuid, text),
  public.udn_prune(),
  public.udn_claim_first_parent(uuid, uuid),
  public.udn_redeem_code(uuid, uuid, text),
  public.udn_decide_device(uuid, uuid, uuid, text),
  public.udn_revoke_device(uuid, uuid, uuid),
  public.udn_heartbeat(uuid, integer, boolean, text, text, integer),
  public.udn_expire_commands(uuid),
  public.udn_claim_commands(uuid, integer),
  public.udn_report_result(uuid, uuid, text, jsonb, text, text),
  public.udn_ack_command(uuid, uuid),
  public.udn_wake_on_command(),
  public.udn_wake_on_registry(),
  public.udn_guard_parent_assignment()
  from public, anon, authenticated;
grant execute on function
  public.udn_audit(uuid, uuid, text, uuid, jsonb),
  public.udn_rate_check(text, integer, integer),
  public.udn_register_nonce(uuid, text),
  public.udn_prune(),
  public.udn_claim_first_parent(uuid, uuid),
  public.udn_redeem_code(uuid, uuid, text),
  public.udn_decide_device(uuid, uuid, uuid, text),
  public.udn_revoke_device(uuid, uuid, uuid),
  public.udn_heartbeat(uuid, integer, boolean, text, text, integer),
  public.udn_expire_commands(uuid),
  public.udn_claim_commands(uuid, integer),
  public.udn_report_result(uuid, uuid, text, jsonb, text, text),
  public.udn_ack_command(uuid, uuid)
  to service_role;
