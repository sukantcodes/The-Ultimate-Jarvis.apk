-- Atomic failed-attempt accounting for the owner password check (service_role only).
create function public.register_owner_failure(p_keys text[], p_max integer, p_lockout_seconds integer)
returns void
language plpgsql security definer set search_path = public, pg_temp
as $$
declare k text;
begin
  foreach k in array p_keys loop
    insert into owner_auth_attempts as a (key, failed_count, last_failed_at, locked_until)
    values (k, 1, now(), case when p_max <= 1 then now() + make_interval(secs => p_lockout_seconds) end)
    on conflict (key) do update set
      failed_count   = case when a.last_failed_at < now() - make_interval(secs => p_lockout_seconds)
                              and (a.locked_until is null or a.locked_until < now())
                            then 1 else a.failed_count + 1 end,
      last_failed_at = now(),
      locked_until   = case
        when (case when a.last_failed_at < now() - make_interval(secs => p_lockout_seconds)
                     and (a.locked_until is null or a.locked_until < now())
                   then 1 else a.failed_count + 1 end) >= p_max
        then now() + make_interval(secs => p_lockout_seconds)
        else a.locked_until end;
  end loop;
end $$;

-- Latest lock expiry across the given keys, or null when none is currently locked.
create function public.owner_locked_until(p_keys text[]) returns timestamptz
language sql security definer set search_path = public, pg_temp stable
as $$
  select max(locked_until) from owner_auth_attempts where key = any (p_keys) and locked_until > now()
$$;

create function public.clear_owner_failures(p_key text) returns void
language sql security definer set search_path = public, pg_temp
as $$ delete from owner_auth_attempts where key = p_key $$;

revoke all on function public.register_owner_failure(text[], integer, integer) from public, anon, authenticated;
revoke all on function public.owner_locked_until(text[])                       from public, anon, authenticated;
revoke all on function public.clear_owner_failures(text)                       from public, anon, authenticated;
grant execute on function public.register_owner_failure(text[], integer, integer) to service_role;
grant execute on function public.owner_locked_until(text[])                       to service_role;
grant execute on function public.clear_owner_failures(text)                       to service_role;
