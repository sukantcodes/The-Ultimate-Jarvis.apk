-- UDN v4: bans, child "circles" (child-to-child control inside consented groups) and the authorisation rule behind them.
--  * Parents may command any other authorized device (including the other parent) and may remove or ban any child.
--  * Children can never command a parent. A child may command another child only when both are accepted members
--    of the same circle, and never for high-sensitivity actions (enforced in the Edge Function).

create table if not exists public.udn_bans (
  account_id uuid primary key,
  email text,
  banned_by uuid references public.udn_devices(id) on delete set null,
  reason text,
  created_at timestamptz not null default now()
);

create table if not exists public.udn_circles (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null,
  name text not null check (char_length(name) between 1 and 40),
  created_by uuid not null references public.udn_devices(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (created_by, name)
);

create table if not exists public.udn_circle_members (
  circle_id uuid not null references public.udn_circles(id) on delete cascade,
  device_id uuid not null references public.udn_devices(id) on delete cascade,
  status text not null check (status in ('invited', 'member')),
  invited_by uuid references public.udn_devices(id) on delete set null,
  created_at timestamptz not null default now(),
  primary key (circle_id, device_id)
);
create index if not exists udn_circle_members_device_idx on public.udn_circle_members (device_id, status);

alter table public.udn_bans enable row level security;
alter table public.udn_circles enable row level security;
alter table public.udn_circle_members enable row level security;
revoke all on public.udn_bans, public.udn_circles, public.udn_circle_members from anon, authenticated;

alter table public.udn_wakeups drop constraint if exists udn_wakeups_kind_check;
alter table public.udn_wakeups add constraint udn_wakeups_kind_check check (kind in ('command', 'registry', 'circle'));

create or replace function public.udn_wake_on_circle() returns trigger
language plpgsql security definer set search_path = public as $$
declare o uuid;
begin
  select owner_id into o from public.udn_devices where id = new.device_id;
  insert into public.udn_wakeups (owner_id, kind, target_device_id) values (o, 'circle', new.device_id);
  return new;
end $$;
drop trigger if exists udn_circle_wake on public.udn_circle_members;
create trigger udn_circle_wake after insert or update on public.udn_circle_members
  for each row execute function public.udn_wake_on_circle();
revoke all on function public.udn_wake_on_circle() from public, anon, authenticated;

-- May p_source send a command (or message) to p_target?
create or replace function public.udn_may_command(p_owner uuid, p_source uuid, p_target uuid)
returns boolean language sql stable security definer set search_path = public, auth as $$
  select p_source <> p_target
    and exists (select 1 from public.udn_devices t where t.id = p_target and t.owner_id = p_owner and t.status = 'authorized')
    and exists (select 1 from public.udn_devices s where s.id = p_source and s.owner_id = p_owner and s.status = 'authorized')
    and (
      public.udn_is_parent_device(p_owner, p_source)
      or (
        not public.udn_is_parent_device(p_owner, p_target)
        and exists (
          select 1 from public.udn_circle_members a
          join public.udn_circle_members b on b.circle_id = a.circle_id
          where a.device_id = p_source and a.status = 'member' and b.device_id = p_target and b.status = 'member')
      )
    );
$$;

-- Ban every device of an account. Parent accounts can never be banned.
create or replace function public.udn_ban_device_account(p_owner uuid, p_actor uuid, p_target uuid, p_reason text)
returns jsonb language plpgsql security definer set search_path = public, auth as $$
declare d public.udn_devices; em text;
begin
  if not public.udn_is_parent_device(p_owner, p_actor) then return jsonb_build_object('ok', false, 'error', 'not_parent'); end if;
  select * into d from public.udn_devices where id = p_target and owner_id = p_owner;
  if d.id is null then return jsonb_build_object('ok', false, 'error', 'not_found'); end if;
  if public.udn_is_parent_device(p_owner, p_target) then return jsonb_build_object('ok', false, 'error', 'cannot_ban_parent'); end if;
  select lower(email) into em from auth.users where id = d.account_id;
  insert into public.udn_bans (account_id, email, banned_by, reason) values (d.account_id, em, p_actor, left(p_reason, 200))
    on conflict (account_id) do update set banned_by = excluded.banned_by, reason = excluded.reason, created_at = now();
  update public.udn_command_queue set status = 'cancelled', completed_at = now(), error_code = 'device_revoked'
   where target_device_id in (select id from public.udn_devices where account_id = d.account_id) and status in ('queued', 'delivered', 'running');
  update public.udn_devices set status = 'revoked', revoked_at = now() where account_id = d.account_id and owner_id = p_owner and status <> 'revoked';
  perform public.udn_audit(p_owner, p_actor, 'account_banned', p_target, null);
  return jsonb_build_object('ok', true);
end $$;

revoke all on function public.udn_may_command(uuid, uuid, uuid), public.udn_ban_device_account(uuid, uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.udn_may_command(uuid, uuid, uuid), public.udn_ban_device_account(uuid, uuid, uuid, text) to service_role;
