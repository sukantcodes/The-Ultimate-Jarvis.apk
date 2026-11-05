-- Aggregated insights for the owner dashboard (service_role only). Returns counts, never raw PII.
create function public.owner_trends(p_days integer default 14) returns jsonb
language plpgsql security definer set search_path = public, auth, pg_temp stable
as $$
declare d integer := greatest(7, least(coalesce(p_days, 14), 30));
        base timestamptz := date_trunc('day', now() at time zone 'UTC') at time zone 'UTC';
begin
  return jsonb_build_object(
    'days', d,
    'daily', (
      select coalesce(jsonb_agg(jsonb_build_object(
        'day', to_char(g.day at time zone 'UTC', 'YYYY-MM-DD'),
        'sign_ins',    (select count(*) from app_events e where e.event_type = 'sign_in'  and e.created_at >= g.day and e.created_at < g.day + interval '1 day'),
        'app_opens',   (select count(*) from app_events e where e.event_type = 'app_open' and e.created_at >= g.day and e.created_at < g.day + interval '1 day'),
        'new_devices', (select count(*) from app_devices x where x.first_seen_at >= g.day and x.first_seen_at < g.day + interval '1 day')
      ) order by g.day), '[]'::jsonb)
      from generate_series(base - (d - 1) * interval '1 day', base, interval '1 day') as g(day)
    ),
    'models', (select coalesce(jsonb_agg(jsonb_build_object('label', label, 'count', c)), '[]'::jsonb) from (
      select coalesce(device_model, 'Unknown') as label, count(*) as c from app_devices group by 1 order by c desc, 1 limit 8) m),
    'android_versions', (select coalesce(jsonb_agg(jsonb_build_object('label', label, 'count', c)), '[]'::jsonb) from (
      select 'Android ' || coalesce(android_version, '?') as label, count(*) as c from app_devices group by 1 order by c desc, 1 limit 8) m),
    'app_versions', (select coalesce(jsonb_agg(jsonb_build_object('label', label, 'count', c)), '[]'::jsonb) from (
      select 'JARVIS ' || coalesce(app_version, '?') as label, count(*) as c from app_devices group by 1 order by c desc, 1 limit 8) m),
    'providers', (select coalesce(jsonb_agg(jsonb_build_object('label', label, 'count', c)), '[]'::jsonb) from (
      select case coalesce(raw_app_meta_data->>'provider', 'unknown') when 'google' then 'Google' when 'email' then 'Email' else initcap(coalesce(raw_app_meta_data->>'provider', 'unknown')) end as label,
             count(*) as c from auth.users group by 1 order by c desc, 1) m),
    'security', jsonb_build_object(
      'locked_now',        (select count(*) from owner_auth_attempts where locked_until > now()),
      'failed_attempts_24h', (select coalesce(sum(failed_count), 0) from owner_auth_attempts where last_failed_at >= now() - interval '24 hours'),
      'active_dashboard_sessions', (select count(*) from dashboard_sessions where revoked_at is null and expires_at > now())
    )
  );
end $$;

revoke all on function public.owner_trends(integer) from public, anon, authenticated;
grant execute on function public.owner_trends(integer) to service_role;
