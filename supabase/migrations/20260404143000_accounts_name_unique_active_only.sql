begin;

drop index if exists public.accounts_user_name_unique_idx;

create unique index if not exists accounts_user_name_unique_active_idx
  on public.accounts (user_id, lower(name))
  where deleted_at is null;

commit;
