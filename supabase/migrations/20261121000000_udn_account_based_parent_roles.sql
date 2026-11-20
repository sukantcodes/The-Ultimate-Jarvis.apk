-- UDN v3: parent privileges belong to the verified parent account, not one device.
-- Keep udn_parent_assignments as a single representative device per parent slot for legacy UI/compatibility.
-- Every active device owned by either allowlisted, verified Supabase account is a parent device.

create or replace function public.udn_is_parent_device(p_owner uuid, p_device uuid)
returns boolean
language sql
stable
security definer
set search_path = public, auth
as $$
  select exists (
    select 1
    from public.udn_devices d
    join auth.users u on u.id = d.account_id
    join public.udn_parent_emails pe on pe.email = lower(u.email)
    where d.id = p_device
      and d.owner_id = p_owner
      and d.status <> 'revoked'
      and u.email_confirmed_at is not null
  );
$$;

revoke all on function public.udn_is_parent_device(uuid, uuid) from public, anon, authenticated;
grant execute on function public.udn_is_parent_device(uuid, uuid) to service_role;

create or replace function public.udn_auto_enroll(p_owner uuid, p_device uuid, p_slot integer)
returns jsonb
language plpgsql
security definer
set search_path = public, auth
as $$
declare
  d public.udn_devices;
  occ public.udn_devices;
  configured_slot smallint;
  assigned_slot smallint;
begin
  perform pg_advisory_xact_lock(hashtextextended('udn:' || p_owner::text, 0));

  select * into d
  from public.udn_devices
  where id = p_device and owner_id = p_owner
  for update;

  if d.id is null or d.status = 'revoked' then
    return jsonb_build_object('ok', false, 'error', 'not_found');
  end if;

  -- Resolve the role from the live, confirmed Supabase account email.
  select pe.slot into configured_slot
  from auth.users u
  join public.udn_parent_emails pe on pe.email = lower(u.email)
  where u.id = d.account_id
    and u.email_confirmed_at is not null;

  if configured_slot is not null then
    -- Parent devices are authorized independently; a slot is only a representative-device pointer.
    update public.udn_devices
       set status = 'authorized',
           authorized_at = coalesce(authorized_at, now()),
           authorized_by = coalesce(authorized_by, p_device),
           account_email = lower((select email from auth.users where id = d.account_id))
     where id = p_device;

    select slot into assigned_slot
    from public.udn_parent_assignments
    where owner_id = p_owner and device_id = p_device;

    if assigned_slot is distinct from configured_slot then
      delete from public.udn_parent_assignments
      where owner_id = p_owner and device_id = p_device;
    end if;

    select dd.* into occ
    from public.udn_parent_assignments pa
    join public.udn_devices dd on dd.id = pa.device_id
    where pa.owner_id = p_owner and pa.slot = configured_slot;

    if occ.id is null then
      insert into public.udn_parent_assignments (owner_id, slot, device_id, assigned_via)
      values (p_owner, configured_slot, p_device, 'email_allowlist');
    elsif occ.account_id <> d.account_id then
      -- Repair a stale/mismatched slot pointer without revoking the other device.
      delete from public.udn_parent_assignments
      where owner_id = p_owner and slot = configured_slot;
      insert into public.udn_parent_assignments (owner_id, slot, device_id, assigned_via)
      values (p_owner, configured_slot, p_device, 'email_allowlist');
    end if;

    perform public.udn_audit(
      p_owner, p_device, 'parent_account_enrolled', p_device,
      jsonb_build_object('slot', configured_slot)
    );

    return jsonb_build_object(
      'ok', true, 'role', 'parent', 'slot', configured_slot, 'status', 'authorized'
    );
  end if;

  -- If a previously allowlisted account changed its email, it no longer retains a parent slot.
  delete from public.udn_parent_assignments
  where owner_id = p_owner and device_id = p_device;

  if d.status = 'pending' then
    update public.udn_devices
       set status = 'authorized', authorized_at = now()
     where id = p_device;
    perform public.udn_audit(p_owner, p_device, 'child_joined', p_device, null);
  end if;

  return jsonb_build_object('ok', true, 'role', 'child', 'status', 'authorized');
end;
$$;

revoke all on function public.udn_auto_enroll(uuid, uuid, integer) from public, anon, authenticated;
grant execute on function public.udn_auto_enroll(uuid, uuid, integer) to service_role;

create or replace function public.udn_decide_device(p_owner uuid, p_actor uuid, p_target uuid, p_decision text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare d public.udn_devices;
begin
  perform pg_advisory_xact_lock(hashtextextended('udn:' || p_owner::text, 0));

  if not public.udn_is_parent_device(p_owner, p_actor) then
    return jsonb_build_object('ok', false, 'error', 'not_parent');
  end if;

  select * into d
  from public.udn_devices
  where id = p_target and owner_id = p_owner
  for update;

  if d.id is null or d.status <> 'pending' then
    return jsonb_build_object('ok', false, 'error', 'device_not_pending');
  end if;

  if p_decision = 'authorize' then
    update public.udn_devices
       set status = 'authorized', authorized_at = now(), authorized_by = p_actor
     where id = p_target;
    perform public.udn_audit(p_owner, p_actor, 'device_authorized', p_target, null);
  elsif p_decision = 'reject' then
    update public.udn_devices
       set status = 'revoked', revoked_at = now()
     where id = p_target;
    perform public.udn_audit(p_owner, p_actor, 'device_rejected', p_target, null);
  else
    return jsonb_build_object('ok', false, 'error', 'invalid_decision');
  end if;

  return jsonb_build_object('ok', true);
end;
$$;

revoke all on function public.udn_decide_device(uuid, uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.udn_decide_device(uuid, uuid, uuid, text) to service_role;

create or replace function public.udn_revoke_device(p_owner uuid, p_actor uuid, p_target uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  d public.udn_devices;
  actor_is_parent boolean;
  target_is_parent boolean;
begin
  perform pg_advisory_xact_lock(hashtextextended('udn:' || p_owner::text, 0));

  actor_is_parent := public.udn_is_parent_device(p_owner, p_actor);
  target_is_parent := public.udn_is_parent_device(p_owner, p_target);

  if not actor_is_parent and p_actor <> p_target then
    return jsonb_build_object('ok', false, 'error', 'not_parent');
  end if;

  if target_is_parent and p_actor <> p_target then
    return jsonb_build_object('ok', false, 'error', 'cannot_revoke_parent');
  end if;

  select * into d
  from public.udn_devices
  where id = p_target and owner_id = p_owner
  for update;

  if d.id is null then
    return jsonb_build_object('ok', false, 'error', 'not_found');
  end if;

  if d.status = 'revoked' then
    return jsonb_build_object('ok', true, 'already', true);
  end if;

  update public.udn_devices
     set status = 'revoked', revoked_at = now()
   where id = p_target;

  delete from public.udn_parent_assignments
  where owner_id = p_owner and device_id = p_target;

  update public.udn_command_queue
     set status = 'cancelled', completed_at = now(), error_code = 'device_revoked'
   where target_device_id = p_target and status in ('queued', 'delivered', 'running');

  update public.udn_device_sessions
     set ended_at = now()
   where device_id = p_target and ended_at is null;

  perform public.udn_audit(
    p_owner, p_actor,
    case when p_actor = p_target then 'device_left' else 'device_revoked' end,
    p_target, null
  );

  return jsonb_build_object('ok', true);
end;
$$;

revoke all on function public.udn_revoke_device(uuid, uuid, uuid) from public, anon, authenticated;
grant execute on function public.udn_revoke_device(uuid, uuid, uuid) to service_role;

-- Re-evaluate every existing active device. Each verified parent account gets all its devices promoted;
-- only the first device per account is stored as the legacy slot representative.
do $$
declare r record;
begin
  for r in
    select d.id, pe.slot
    from public.udn_devices d
    join auth.users u on u.id = d.account_id
    join public.udn_parent_emails pe on pe.email = lower(u.email)
    where d.owner_id = '00000000-0000-4000-8000-0000000000a1'
      and d.status <> 'revoked'
      and u.email_confirmed_at is not null
    order by d.created_at
  loop
    perform public.udn_auto_enroll(
      '00000000-0000-4000-8000-0000000000a1', r.id, r.slot
    );
  end loop;
end;
$$;
