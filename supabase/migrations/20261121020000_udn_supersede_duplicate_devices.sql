-- A reinstall (or identity reset) creates a new device row for the same phone. Older rows of the same account and
-- hardware that have been silent for 10+ minutes are superseded, so one phone appears once.
create or replace function public.udn_supersede_devices(p_owner uuid, p_device uuid)
returns integer language plpgsql security definer set search_path = public as $$
declare me public.udn_devices; ids uuid[]; n integer;
begin
  select * into me from public.udn_devices where id = p_device and owner_id = p_owner;
  if me.id is null then return 0; end if;
  select coalesce(array_agg(d.id), '{}') into ids from public.udn_devices d
   where d.owner_id = p_owner and d.account_id = me.account_id and d.platform = me.platform
     and coalesce(d.manufacturer, '') = coalesce(me.manufacturer, '') and coalesce(d.model, '') = coalesce(me.model, '')
     and d.id <> me.id and d.status <> 'revoked' and d.created_at < me.created_at
     and (d.last_seen_at is null or d.last_seen_at < now() - interval '10 minutes');
  n := coalesce(array_length(ids, 1), 0);
  if n > 0 then
    update public.udn_command_queue set status = 'cancelled', completed_at = now(), error_code = 'device_revoked'
     where target_device_id = any(ids) and status in ('queued', 'delivered', 'running');
    delete from public.udn_parent_assignments where device_id = any(ids);
    update public.udn_devices set status = 'revoked', revoked_at = now() where id = any(ids);
  end if;
  return n;
end $$;
revoke all on function public.udn_supersede_devices(uuid, uuid) from public, anon, authenticated;
grant execute on function public.udn_supersede_devices(uuid, uuid) to service_role;

-- one-time clean-up of the duplicates that already exist
select public.udn_supersede_devices(owner_id, id) from public.udn_devices where status <> 'revoked';
