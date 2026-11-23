-- JARVIS OTA update metadata.
--
-- APK files live in GitHub Releases and are NEVER stored in Supabase Storage. This schema holds only
-- small metadata rows: the branch registry and one row per published release. Clients read through the
-- `ota-updates` Edge Function; GitHub Actions publishes through the `ota-publish` Edge Function, which
-- verifies a GitHub OIDC token. Neither anon nor authenticated roles can touch these tables directly, and
-- no service-role key is stored in the Android app or in GitHub.
--
-- Additive only: the older `app_releases` table, `app-updates` function and `jarvis-releases` bucket are
-- left untouched.

create table if not exists public.ota_branches (
  branch        text primary key
                check (branch ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,98}$'),
  is_default    boolean not null default false,
  is_enabled    boolean not null default true,
  derived_from  text not null default 'Antigravity',
  description   text not null default '' check (char_length(description) <= 200),
  registered_by text not null default 'system' check (char_length(registered_by) <= 100),
  registered_at timestamptz not null default now(),
  check (not is_default or is_enabled)
);

-- Exactly one default branch.
create unique index if not exists ota_branches_single_default
  on public.ota_branches (is_default) where is_default;

insert into public.ota_branches (branch, is_default, derived_from, description, registered_by)
values ('Antigravity', true, 'Antigravity', 'Primary JARVIS update branch', 'migration')
on conflict (branch) do nothing;

create table if not exists public.ota_releases (
  id            uuid primary key default gen_random_uuid(),
  branch        text not null references public.ota_branches (branch),
  version_code  integer not null check (version_code > 0),
  version_name  text not null check (char_length(version_name) between 1 and 40),
  package_name  text not null check (package_name = 'com.jarvis.assistant'),
  tag           text not null check (tag ~ '^[A-Za-z0-9._-]{1,120}$'),
  asset_name    text not null check (asset_name ~ '^[A-Za-z0-9._-]{1,120}\.apk$'),
  apk_url       text not null
                check (apk_url ~* '^https://github\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\.apk$'),
  size_bytes    bigint not null check (size_bytes between 1 and 400000000),
  sha256        text not null check (sha256 ~ '^[A-F0-9]{64}$'),
  cert_sha256   text not null check (cert_sha256 ~ '^[A-F0-9]{64}$'),
  min_sdk       integer not null default 26 check (min_sdk between 1 and 99),
  min_supported_version_code integer not null default 1 check (min_supported_version_code > 0),
  release_notes text not null default '' check (char_length(release_notes) <= 8000),
  commit_sha    text not null check (commit_sha ~ '^[0-9a-f]{40}$'),
  run_id        bigint,
  run_url       text check (run_url is null or run_url ~ '^https://github\.com/'),
  status        text not null default 'published' check (status in ('published', 'revoked')),
  published_at  timestamptz not null default now(),
  unique (branch, version_code),
  check (min_supported_version_code <= version_code)
);

create index if not exists ota_releases_branch_version_idx
  on public.ota_releases (branch, version_code desc) where status = 'published';

alter table public.ota_branches enable row level security;
alter table public.ota_branches force row level security;
alter table public.ota_releases enable row level security;
alter table public.ota_releases force row level security;
-- No policies on purpose: with RLS forced and no policy, only service_role (which bypasses RLS) can use them.
revoke all on public.ota_branches from anon, authenticated;
revoke all on public.ota_releases from anon, authenticated;
grant select, insert, update on public.ota_branches to service_role;
grant select, insert, update on public.ota_releases to service_role;
-- Nothing is ever deleted: previous releases are retained for recovery. Withdraw a release by setting status='revoked'.

-- Registers a branch (or re-enables one). Called only by the ota-publish Edge Function after it has verified
-- the caller's GitHub OIDC token, the repository owner and the branch ancestry.
create or replace function public.ota_register_branch(
  p_branch text, p_derived_from text, p_description text, p_registered_by text
) returns public.ota_branches
language plpgsql security definer set search_path = public, pg_temp
as $$
declare v_row public.ota_branches;
begin
  if p_branch is null or p_branch !~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,98}$'
     or lower(p_branch) in ('main', 'master') then
    raise exception 'invalid_branch';
  end if;
  insert into public.ota_branches (branch, is_default, is_enabled, derived_from, description, registered_by)
  values (p_branch, false, true, coalesce(nullif(p_derived_from, ''), 'Antigravity'),
          left(coalesce(p_description, ''), 200), left(coalesce(p_registered_by, 'unknown'), 100))
  on conflict (branch) do update
    set is_enabled = true,
        description = excluded.description
    where public.ota_branches.is_default = false
  returning * into v_row;
  if v_row.branch is null then
    select * into v_row from public.ota_branches where branch = p_branch;
  end if;
  return v_row;
end;
$$;

-- Disables a non-default branch. The default branch can never be disabled this way.
create or replace function public.ota_disable_branch(p_branch text)
returns public.ota_branches
language plpgsql security definer set search_path = public, pg_temp
as $$
declare v_row public.ota_branches;
begin
  update public.ota_branches set is_enabled = false
    where branch = p_branch and is_default = false
    returning * into v_row;
  if v_row.branch is null then
    raise exception 'branch_not_disableable';
  end if;
  return v_row;
end;
$$;

-- Publishes one release. Runs only after GitHub Actions has built, signed, verified and uploaded the APK and
-- the Edge Function has independently confirmed the GitHub release asset (size and SHA-256).
create or replace function public.ota_publish_release(
  p_branch text, p_version_code integer, p_version_name text, p_tag text, p_asset_name text,
  p_apk_url text, p_size_bytes bigint, p_sha256 text, p_cert_sha256 text, p_min_sdk integer,
  p_min_supported_version_code integer, p_release_notes text, p_commit_sha text,
  p_run_id bigint, p_run_url text
) returns public.ota_releases
language plpgsql security definer set search_path = public, pg_temp
as $$
declare v_row public.ota_releases;
begin
  if not exists (select 1 from public.ota_branches where branch = p_branch and is_enabled) then
    raise exception 'branch_not_eligible';
  end if;
  -- Strictly increasing per branch: a late or replayed build can never roll the feed back.
  if exists (select 1 from public.ota_releases
             where branch = p_branch and status = 'published' and version_code >= p_version_code) then
    raise exception 'release_version_must_increase';
  end if;
  insert into public.ota_releases (
    branch, version_code, version_name, package_name, tag, asset_name, apk_url, size_bytes, sha256,
    cert_sha256, min_sdk, min_supported_version_code, release_notes, commit_sha, run_id, run_url)
  values (
    p_branch, p_version_code, p_version_name, 'com.jarvis.assistant', p_tag, p_asset_name, p_apk_url,
    p_size_bytes, upper(p_sha256), upper(p_cert_sha256), p_min_sdk, p_min_supported_version_code,
    coalesce(p_release_notes, ''), p_commit_sha, p_run_id, p_run_url)
  returning * into v_row;
  return v_row;
end;
$$;

revoke all on function public.ota_register_branch(text, text, text, text) from public, anon, authenticated;
revoke all on function public.ota_disable_branch(text) from public, anon, authenticated;
revoke all on function public.ota_publish_release(
  text, integer, text, text, text, text, bigint, text, text, integer, integer, text, text, bigint, text
) from public, anon, authenticated;
grant execute on function public.ota_register_branch(text, text, text, text) to service_role;
grant execute on function public.ota_disable_branch(text) to service_role;
grant execute on function public.ota_publish_release(
  text, integer, text, text, text, text, bigint, text, text, integer, integer, text, text, bigint, text
) to service_role;
