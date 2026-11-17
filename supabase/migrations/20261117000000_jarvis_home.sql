-- JARVIS Home: device registry sync, Home Hub presence and expiring, deduplicated remote commands.
-- Ownership model: every row belongs to one auth user (owner_id). The phone and the Home Hub both sign in as that user with the
-- publishable key; no service_role key is used by either. Device passwords are never stored here.

create table if not exists public.home_hubs (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 60),
  platform text not null default 'windows' check (char_length(platform) <= 20),
  last_seen_at timestamptz not null default now(),
  created_at timestamptz not null default now()
);
create index if not exists home_hubs_owner_idx on public.home_hubs(owner_id);

create table if not exists public.home_devices (
  id uuid primary key,
  owner_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 60),
  manufacturer text not null default '' check (char_length(manufacturer) <= 60),
  category text not null default 'OTHER',
  connection text not null,
  protocol text not null,
  address text not null default '' check (char_length(address) <= 120),
  room_name text,
  capabilities text[] not null default '{}',
  config jsonb not null default '{}'::jsonb,   -- protocol settings only (URLs, relay index). Never credentials.
  status text not null default 'UNKNOWN' check (status in ('ONLINE','OFFLINE','UNKNOWN')),
  powered_on boolean,
  hub_id uuid references public.home_hubs(id) on delete set null,
  last_seen_at timestamptz,
  updated_at timestamptz not null default now()
);
create index if not exists home_devices_owner_idx on public.home_devices(owner_id);

create table if not exists public.home_commands (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  device_id uuid not null references public.home_devices(id) on delete cascade,
  hub_id uuid references public.home_hubs(id) on delete set null,
  action jsonb not null,
  status text not null default 'pending' check (status in ('pending','running','succeeded','failed','expired')),
  verified boolean not null default false,
  result_message text not null default '' check (char_length(result_message) <= 300),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  acked_at timestamptz,
  check (expires_at > created_at)
);
create index if not exists home_commands_owner_idx on public.home_commands(owner_id, created_at desc);
create index if not exists home_commands_pending_idx on public.home_commands(owner_id, status) where status in ('pending','running');

alter table public.home_hubs enable row level security;
alter table public.home_devices enable row level security;
alter table public.home_commands enable row level security;
revoke all on public.home_hubs, public.home_devices, public.home_commands from anon;

-- Hubs and devices: full control for their owner only.
drop policy if exists home_hubs_owner on public.home_hubs;
create policy home_hubs_owner on public.home_hubs for all to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

drop policy if exists home_devices_owner on public.home_devices;
create policy home_devices_owner on public.home_devices for all to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

-- Commands: the owner can read them and create new pending ones for their own devices that expire within 10 minutes.
-- Status changes happen only through the functions below, so a command cannot be revived, edited or re-run.
drop policy if exists home_commands_read on public.home_commands;
create policy home_commands_read on public.home_commands for select to authenticated using (owner_id = auth.uid());

drop policy if exists home_commands_insert on public.home_commands;
create policy home_commands_insert on public.home_commands for insert to authenticated
  with check (
    owner_id = auth.uid()
    and status = 'pending' and verified = false and acked_at is null
    and expires_at > now() and expires_at <= now() + interval '10 minutes'
    and exists (select 1 from public.home_devices d where d.id = device_id and d.owner_id = auth.uid())
  );

grant select, insert, update, delete on public.home_hubs to authenticated;
grant select, insert, update, delete on public.home_devices to authenticated;
grant select, insert on public.home_commands to authenticated;

-- A hub claims the oldest live pending command for its owner. SKIP LOCKED + the status change make a command run at most once,
-- even with two hubs polling. Expired commands are marked expired instead of being handed out.
create or replace function public.home_claim_command(p_hub_id uuid)
returns setof public.home_commands
language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_row public.home_commands;
begin
  if v_uid is null then raise exception 'not authenticated'; end if;
  if not exists (select 1 from public.home_hubs h where h.id = p_hub_id and h.owner_id = v_uid) then
    raise exception 'unknown hub';
  end if;
  update public.home_hubs set last_seen_at = now() where id = p_hub_id;
  update public.home_commands set status = 'expired', acked_at = now(), result_message = 'Expired before a hub picked it up'
    where owner_id = v_uid and status = 'pending' and expires_at <= now();
  select * into v_row from public.home_commands
    where owner_id = v_uid and status = 'pending' and expires_at > now()
    order by created_at limit 1 for update skip locked;
  if found then
    update public.home_commands set status = 'running', hub_id = p_hub_id where id = v_row.id returning * into v_row;
    return next v_row;
  end if;
  return;
end $$;

-- The claiming hub reports the real outcome. Only a running command owned by this user and this hub can be acknowledged.
create or replace function public.home_ack_command(p_command_id uuid, p_hub_id uuid, p_success boolean, p_verified boolean, p_message text)
returns void
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'not authenticated'; end if;
  update public.home_commands
     set status = case when p_success then 'succeeded' else 'failed' end,
         verified = coalesce(p_verified, false) and p_success,
         result_message = left(coalesce(p_message, ''), 300),
         acked_at = now()
   where id = p_command_id and owner_id = v_uid and hub_id = p_hub_id and status = 'running';
  if not found then raise exception 'command is not running on this hub'; end if;
end $$;

revoke all on function public.home_claim_command(uuid) from public, anon;
revoke all on function public.home_ack_command(uuid, uuid, boolean, boolean, text) from public, anon;
grant execute on function public.home_claim_command(uuid) to authenticated;
grant execute on function public.home_ack_command(uuid, uuid, boolean, boolean, text) to authenticated;
