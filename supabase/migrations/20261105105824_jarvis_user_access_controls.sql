-- Owner-managed access blocks for accounts, per-install IDs and server-observed IPs.
create table if not exists public.access_blocks (
  id uuid primary key default gen_random_uuid(),
  block_type text not null check (block_type in ('user','device','ip')),
  target_value text not null check (char_length(target_value) between 2 and 128),
  reason text not null default '' check (char_length(reason) <= 240),
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  unique (block_type, target_value)
);
create index if not exists access_blocks_type_target_idx on public.access_blocks(block_type, target_value);
alter table public.access_blocks enable row level security;
alter table public.access_blocks force row level security;
revoke all on public.access_blocks from anon, authenticated;
grant select, insert, update, delete on public.access_blocks to service_role;
