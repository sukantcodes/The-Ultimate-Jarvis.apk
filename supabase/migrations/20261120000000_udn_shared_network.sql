-- UDN v2: ONE shared network for all JARVIS accounts.
--  * owner_id is now the constant network id; the real signed-in account lives in udn_devices.account_id.
--  * Two fixed parent accounts (udn_parent_emails). A device registered by a parent account takes that parent slot.
--  * Every other device is authorized automatically when it joins (the user consents on the device itself) and is
--    controllable by the parents; parents can remove any child. Pairing codes are retired.
--  * Parent-to-device messages are stored in udn_messages.

do $$
declare r record;
begin
  for r in
    select c.conrelid::regclass::text as t, c.conname from pg_constraint c
    join pg_class k on k.oid = c.conrelid join pg_namespace n on n.oid = k.relnamespace
    where c.contype = 'f' and c.confrelid = 'auth.users'::regclass and n.nspname = 'public' and k.relname like 'udn\_%'
  loop
    execute format('alter table %s drop constraint %I', r.t, r.conname);
  end loop;
end $$;

alter table public.udn_devices add column if not exists account_id uuid;
alter table public.udn_devices add column if not exists account_email text;
update public.udn_devices set account_id = owner_id where account_id is null;
update public.udn_devices d set account_email = lower(u.email) from auth.users u where u.id = d.account_id and d.account_email is null;
alter table public.udn_devices alter column account_id set not null;
create index if not exists udn_devices_account_idx on public.udn_devices (account_id);

-- fold every account's data into the one network
delete from public.udn_parent_assignments;
update public.udn_devices set owner_id = '00000000-0000-4000-8000-0000000000a1';
update public.udn_command_queue set owner_id = '00000000-0000-4000-8000-0000000000a1';
update public.udn_audit_logs set owner_id = '00000000-0000-4000-8000-0000000000a1';
update public.udn_wakeups set owner_id = '00000000-0000-4000-8000-0000000000a1';
update public.udn_pairing_requests set owner_id = '00000000-0000-4000-8000-0000000000a1';
do $$ begin
  begin update public.udn_device_groups set owner_id = '00000000-0000-4000-8000-0000000000a1'; exception when unique_violation then null; end;
end $$;

alter table public.udn_parent_assignments drop constraint if exists udn_parent_assignments_assigned_via_check;
alter table public.udn_parent_assignments add constraint udn_parent_assignments_assigned_via_check
  check (assigned_via in ('first_claim', 'pairing_code', 'email_allowlist'));

create table if not exists public.udn_parent_emails (
  email text primary key check (email = lower(email)),
  slot smallint not null unique check (slot in (1, 2))
);
alter table public.udn_parent_emails enable row level security;
revoke all on public.udn_parent_emails from anon, authenticated;
insert into public.udn_parent_emails (email, slot) values
  ('sukantxplodeprime@gmail.com', 1), ('sentubhattacharya2021@gmail.com', 2)
on conflict (email) do nothing;

create table if not exists public.udn_messages (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null,
  from_device_id uuid not null references public.udn_devices(id) on delete cascade,
  to_device_id uuid not null references public.udn_devices(id) on delete cascade,
  body text not null check (char_length(body) between 1 and 500),
  command_id uuid,
  created_at timestamptz not null default now()
);
create index if not exists udn_messages_to_idx on public.udn_messages (to_device_id, created_at desc);
create index if not exists udn_messages_owner_idx on public.udn_messages (owner_id, created_at desc);
alter table public.udn_messages enable row level security;
revoke all on public.udn_messages from anon, authenticated;

-- Realtime wake-ups: visible only to accounts that own a non-revoked device in the network.
drop policy if exists udn_wakeups_owner_select on public.udn_wakeups;
create policy udn_wakeups_owner_select on public.udn_wakeups
  for select to authenticated using (
    exists (select 1 from public.udn_devices d
            where d.account_id = (select auth.uid()) and d.status <> 'revoked' and d.owner_id = udn_wakeups.owner_id));

-- Decides a newly registered (or still pending) device's role. p_slot is non-null only for a parent account.
create or replace function public.udn_auto_enroll(p_owner uuid, p_device uuid, p_slot integer)
returns jsonb language plpgsql security definer set search_path = public as $$
declare d public.udn_devices; occ public.udn_devices;
begin
  perform pg_advisory_xact_lock(hashtextextended('udn:' || p_owner::text, 0));
  select * into d from public.udn_devices where id = p_device and owner_id = p_owner for update;
  if d.id is null or d.status = 'revoked' then return jsonb_build_object('ok', false, 'error', 'not_found'); end if;
  if exists (select 1 from public.udn_parent_assignments where device_id = p_device) then
    return jsonb_build_object('ok', true, 'role', 'parent', 'status', d.status);
  end if;
  if p_slot is not null then
    select dd.* into occ from public.udn_parent_assignments pa join public.udn_devices dd on dd.id = pa.device_id
     where pa.owner_id = p_owner and pa.slot = p_slot;
    -- The slot is free, or its previous device is gone (revoked) or has been silent for 7 days (lost/reinstalled phone).
    if occ.id is not null and occ.status <> 'revoked' and coalesce(occ.last_seen_at, occ.created_at) > now() - interval '7 days' then
      p_slot := null;
    elsif occ.id is not null then
      delete from public.udn_parent_assignments where owner_id = p_owner and slot = p_slot;
      update public.udn_devices set status = 'revoked', revoked_at = now() where id = occ.id;
      perform public.udn_audit(p_owner, p_device, 'parent_replaced', occ.id, jsonb_build_object('slot', p_slot));
    end if;
  end if;
  if p_slot is not null then
    insert into public.udn_parent_assignments (owner_id, slot, device_id, assigned_via) values (p_owner, p_slot, p_device, 'email_allowlist');
    update public.udn_devices set status = 'authorized', authorized_at = coalesce(authorized_at, now()), authorized_by = p_device where id = p_device;
    perform public.udn_audit(p_owner, p_device, 'parent_enrolled', p_device, jsonb_build_object('slot', p_slot));
    return jsonb_build_object('ok', true, 'role', 'parent', 'slot', p_slot, 'status', 'authorized');
  end if;
  if d.status = 'pending' then
    update public.udn_devices set status = 'authorized', authorized_at = now() where id = p_device;
    perform public.udn_audit(p_owner, p_device, 'child_joined', p_device, null);
  end if;
  return jsonb_build_object('ok', true, 'role', 'child', 'status', 'authorized');
end $$;
revoke all on function public.udn_auto_enroll(uuid, uuid, integer) from public, anon, authenticated;
grant execute on function public.udn_auto_enroll(uuid, uuid, integer) to service_role;

-- Re-enroll the devices that already exist under the new rules (oldest device of each parent account takes its slot).
do $$
declare r record;
begin
  for r in
    select d.id, pe.slot from public.udn_devices d
    left join public.udn_parent_emails pe on pe.email = d.account_email
    where d.status <> 'revoked'
    order by d.created_at
  loop
    perform public.udn_auto_enroll('00000000-0000-4000-8000-0000000000a1', r.id, r.slot);
  end loop;
end $$;
