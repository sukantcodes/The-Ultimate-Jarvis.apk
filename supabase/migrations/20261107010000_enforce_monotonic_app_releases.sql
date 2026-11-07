-- Prevent accidental rollback or duplicate active release publication.
create or replace function public.publish_app_release(
  p_channel text, p_version_code integer, p_version_name text, p_apk_path text,
  p_apk_url text, p_sha256 text, p_min_supported_version_code integer, p_release_notes text
) returns public.app_releases
language plpgsql security definer set search_path = public, pg_temp
as $$
declare v_release public.app_releases;
begin
  if p_channel not in ('stable','beta') or p_version_code <= 0
     or p_min_supported_version_code <= 0 or p_min_supported_version_code > p_version_code
     or p_version_name is null or char_length(p_version_name) not between 1 and 40
     or p_apk_path is null or char_length(p_apk_path) not between 1 and 500
     or p_apk_url is null or p_apk_url !~ '^https://'
     or p_sha256 is null or p_sha256 !~ '^[A-Fa-f0-9]{64}$'
     or char_length(coalesce(p_release_notes,'')) > 8000 then
    raise exception 'invalid_release_metadata';
  end if;
  if exists (
    select 1 from public.app_releases
    where channel = p_channel and is_active = true and version_code >= p_version_code
  ) then
    raise exception 'release_version_must_increase';
  end if;
  update public.app_releases set is_active = false
    where channel = p_channel and is_active = true;
  insert into public.app_releases(channel, version_code, version_name, apk_path, apk_url, sha256,
    min_supported_version_code, release_notes, is_active, published_at)
  values (p_channel, p_version_code, p_version_name, p_apk_path, p_apk_url, upper(p_sha256),
    p_min_supported_version_code, p_release_notes, true, now())
  returning * into v_release;
  return v_release;
end;
$$;
revoke all on function public.publish_app_release(text,integer,text,text,text,text,integer,text)
  from public, anon, authenticated;
grant execute on function public.publish_app_release(text,integer,text,text,text,text,integer,text)
  to service_role;
