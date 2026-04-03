-- Secure backend foundation for Expenso
-- 1) Remove insecure legacy auth table in public schema
-- 2) Standardize profile table around auth.users
-- 3) Add accounts + transactions with RLS and server-side balance integrity

begin;

create extension if not exists pgcrypto;

-- Drop legacy table that stored password hashes outside Supabase Auth.
drop table if exists public.users cascade;

create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create table if not exists public.user_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  email text not null,
  full_name text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.user_profiles
  alter column email set not null,
  alter column created_at type timestamptz using created_at::timestamptz,
  alter column created_at set default now(),
  alter column created_at set not null,
  alter column updated_at type timestamptz using updated_at::timestamptz,
  alter column updated_at set default now(),
  alter column updated_at set not null;

create unique index if not exists user_profiles_email_unique_idx
  on public.user_profiles (lower(email));

alter table public.user_profiles enable row level security;

-- Replace any legacy policies with strict authenticated-only ownership checks.
drop policy if exists "Users can view their own profile" on public.user_profiles;
drop policy if exists "Users can update their own profile" on public.user_profiles;
drop policy if exists "Users can insert their own profile" on public.user_profiles;
drop policy if exists "user_profiles_select_own" on public.user_profiles;
drop policy if exists "user_profiles_insert_own" on public.user_profiles;
drop policy if exists "user_profiles_update_own" on public.user_profiles;

create policy "user_profiles_select_own"
on public.user_profiles
for select
to authenticated
using (auth.uid() = id);

create policy "user_profiles_insert_own"
on public.user_profiles
for insert
to authenticated
with check (auth.uid() = id);

create policy "user_profiles_update_own"
on public.user_profiles
for update
to authenticated
using (auth.uid() = id)
with check (auth.uid() = id);

drop trigger if exists user_profiles_set_updated_at on public.user_profiles;
create trigger user_profiles_set_updated_at
before update on public.user_profiles
for each row execute function public.set_updated_at();

create table if not exists public.accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  opening_balance numeric(14,2) not null default 0,
  current_balance numeric(14,2) not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint accounts_name_not_blank check (char_length(trim(name)) > 0),
  constraint accounts_balance_range check (
    opening_balance between -999999999999.99 and 999999999999.99
    and current_balance between -999999999999.99 and 999999999999.99
  ),
  constraint accounts_id_user_unique unique (id, user_id)
);

create unique index if not exists accounts_user_name_unique_idx
  on public.accounts (user_id, lower(name));

alter table public.accounts enable row level security;

drop policy if exists "accounts_select_own" on public.accounts;
drop policy if exists "accounts_insert_own" on public.accounts;
drop policy if exists "accounts_update_own" on public.accounts;
drop policy if exists "accounts_delete_own" on public.accounts;

create policy "accounts_select_own"
on public.accounts
for select
to authenticated
using (auth.uid() = user_id);

create policy "accounts_insert_own"
on public.accounts
for insert
to authenticated
with check (auth.uid() = user_id);

create policy "accounts_update_own"
on public.accounts
for update
to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

create policy "accounts_delete_own"
on public.accounts
for delete
to authenticated
using (auth.uid() = user_id);

create or replace function public.accounts_set_current_from_opening()
returns trigger
language plpgsql
as $$
begin
  new.current_balance = coalesce(new.opening_balance, 0);
  return new;
end;
$$;

drop trigger if exists accounts_set_current_from_opening on public.accounts;
create trigger accounts_set_current_from_opening
before insert on public.accounts
for each row execute function public.accounts_set_current_from_opening();

drop trigger if exists accounts_set_updated_at on public.accounts;
create trigger accounts_set_updated_at
before update on public.accounts
for each row execute function public.set_updated_at();

create table if not exists public.transactions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  account_id uuid not null,
  title text not null,
  amount numeric(14,2) not null,
  transaction_type text not null,
  tag text not null default 'General',
  occurred_on date not null default ((now() at time zone 'utc')::date),
  note text not null default '',
  is_transfer boolean not null default false,
  transfer_group_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint transactions_account_owner_fk
    foreign key (account_id, user_id)
    references public.accounts (id, user_id)
    on delete cascade,
  constraint transactions_title_not_blank check (char_length(trim(title)) > 0),
  constraint transactions_amount_positive check (amount > 0),
  constraint transactions_type_valid check (transaction_type in ('Income', 'Expense'))
);

create index if not exists transactions_user_id_created_at_idx
  on public.transactions (user_id, created_at desc);
create index if not exists transactions_account_id_created_at_idx
  on public.transactions (account_id, created_at desc);
create index if not exists transactions_user_id_occurred_on_idx
  on public.transactions (user_id, occurred_on desc);
create index if not exists transactions_transfer_group_idx
  on public.transactions (transfer_group_id)
  where transfer_group_id is not null;

alter table public.transactions enable row level security;

drop policy if exists "transactions_select_own" on public.transactions;
drop policy if exists "transactions_insert_own" on public.transactions;
drop policy if exists "transactions_update_own" on public.transactions;
drop policy if exists "transactions_delete_own" on public.transactions;

create policy "transactions_select_own"
on public.transactions
for select
to authenticated
using (auth.uid() = user_id);

create policy "transactions_insert_own"
on public.transactions
for insert
to authenticated
with check (auth.uid() = user_id);

create policy "transactions_update_own"
on public.transactions
for update
to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

create policy "transactions_delete_own"
on public.transactions
for delete
to authenticated
using (auth.uid() = user_id);

create or replace function public.apply_transaction_balance_delta()
returns trigger
language plpgsql
as $$
declare
  old_delta numeric(14,2);
  new_delta numeric(14,2);
begin
  if tg_op = 'INSERT' then
    new_delta := case when new.transaction_type = 'Income' then new.amount else -new.amount end;

    update public.accounts
    set current_balance = current_balance + new_delta
    where id = new.account_id
      and user_id = new.user_id;

    return new;
  elsif tg_op = 'DELETE' then
    old_delta := case when old.transaction_type = 'Income' then old.amount else -old.amount end;

    update public.accounts
    set current_balance = current_balance - old_delta
    where id = old.account_id
      and user_id = old.user_id;

    return old;
  else
    old_delta := case when old.transaction_type = 'Income' then old.amount else -old.amount end;
    new_delta := case when new.transaction_type = 'Income' then new.amount else -new.amount end;

    if old.account_id = new.account_id and old.user_id = new.user_id then
      update public.accounts
      set current_balance = current_balance + (new_delta - old_delta)
      where id = new.account_id
        and user_id = new.user_id;
    else
      update public.accounts
      set current_balance = current_balance - old_delta
      where id = old.account_id
        and user_id = old.user_id;

      update public.accounts
      set current_balance = current_balance + new_delta
      where id = new.account_id
        and user_id = new.user_id;
    end if;

    return new;
  end if;
end;
$$;

drop trigger if exists transactions_apply_balance_delta on public.transactions;
create trigger transactions_apply_balance_delta
after insert or update or delete on public.transactions
for each row execute function public.apply_transaction_balance_delta();

drop trigger if exists transactions_set_updated_at on public.transactions;
create trigger transactions_set_updated_at
before update on public.transactions
for each row execute function public.set_updated_at();

create or replace function public.sync_profile_from_auth_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if new.email is null then
    return new;
  end if;

  insert into public.user_profiles (id, email, full_name)
  values (
    new.id,
    new.email,
    nullif(trim(coalesce(new.raw_user_meta_data ->> 'full_name', '')), '')
  )
  on conflict (id)
  do update
  set email = excluded.email,
      full_name = coalesce(excluded.full_name, public.user_profiles.full_name),
      updated_at = now();

  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row execute function public.sync_profile_from_auth_user();

drop trigger if exists on_auth_user_updated on auth.users;
create trigger on_auth_user_updated
after update of email, raw_user_meta_data on auth.users
for each row execute function public.sync_profile_from_auth_user();

-- Backfill profiles for existing users.
insert into public.user_profiles (id, email, full_name)
select
  u.id,
  u.email,
  nullif(trim(coalesce(u.raw_user_meta_data ->> 'full_name', '')), '')
from auth.users u
where u.email is not null
on conflict (id)
do update
set email = excluded.email,
    full_name = coalesce(excluded.full_name, public.user_profiles.full_name),
    updated_at = now();

commit;
