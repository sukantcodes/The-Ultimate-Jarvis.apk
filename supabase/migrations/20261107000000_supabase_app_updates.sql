-- JARVIS Supabase-only app update delivery.
-- Release metadata and publishing are server-only; Android clients can only call the read endpoint.
create table if not exists public.app_releases (
  id uuid primary key default gen_random_uuid(),
  channel text not null default 'stable' check (channel in ('stable','beta')),
  version_code integer not null check (version_code > 0),
  version_name text not null check (char_length(version_name) between 1 and 40),
  apk_path text not null check (char_length(apk_path) between 1 and 500),
  apk_url text not null check (apk_url ~ '^https://'),
  sha256 text not null check (sha256 ~ '^[A-Fa-f0-9]{64}$'),
  min_supported_version_code integer not null default 1 check (min_supported_version_code > 0),
  release_notes text not null default '' check (char_length(release_notes) <= 8000),
  is_active boolean not null default true,
  published_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique(channel, version_code)
);
create index if not exists app_releases_active_channel_version_idx
  on public.app_releases(channel, version_code desc) where is_active;
alter table public.app_releases enable row level security;
alter table public.app_releases force row level security;
revoke all on public.app_releases from anon, authenticated;
grant select, insert, update, delete on public.app_releases to service_role;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('jarvis-releases', 'jarvis-releases', true, 250000000,
        array['application/vnd.android.package-archive','application/octet-stream'])
on conflict (id) do update
set public = true, file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

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
  update public.app_releases set is_active = false where channel = p_channel and is_active = true;
  insert into public.app_releases(channel, version_code, version_name, apk_path, apk_url, sha256,
    min_supported_version_code, release_notes, is_active, published_at)
  values (p_channel, p_version_code, p_version_name, p_apk_path, p_apk_url, upper(p_sha256),
    p_min_supported_version_code, coalesce(p_release_notes,''), true, now())
  returning * into v_release;
  return v_release;
end;
$$;
revoke all on function public.publish_app_release(text,integer,text,text,text,text,integer,text) from public, anon, authenticated;
grant execute on function public.publish_app_release(text,integer,text,text,text,text,integer,text) to service_role;
