begin;

alter table if exists public.accounts
  add column if not exists deleted_at timestamptz;

alter table if exists public.transactions
  add column if not exists deleted_at timestamptz;

create index if not exists accounts_user_active_idx
  on public.accounts (user_id, created_at desc)
  where deleted_at is null;

create index if not exists transactions_user_active_idx
  on public.transactions (user_id, created_at desc)
  where deleted_at is null;

create or replace function public.apply_transaction_balance_delta()
returns trigger
language plpgsql
set search_path = public
as $$
declare
  old_delta numeric(14,2) := 0;
  new_delta numeric(14,2) := 0;
begin
  if tg_op in ('UPDATE', 'DELETE') and old.deleted_at is null then
    old_delta := case when old.transaction_type = 'Income' then old.amount else -old.amount end;
  end if;

  if tg_op in ('UPDATE', 'INSERT') and new.deleted_at is null then
    new_delta := case when new.transaction_type = 'Income' then new.amount else -new.amount end;
  end if;

  if tg_op = 'INSERT' then
    if new_delta <> 0 then
      update public.accounts
      set current_balance = current_balance + new_delta
      where id = new.account_id
        and user_id = new.user_id;
    end if;
    return new;
  elsif tg_op = 'DELETE' then
    if old_delta <> 0 then
      update public.accounts
      set current_balance = current_balance - old_delta
      where id = old.account_id
        and user_id = old.user_id;
    end if;
    return old;
  else
    if old.account_id = new.account_id and old.user_id = new.user_id then
      if new_delta <> old_delta then
        update public.accounts
        set current_balance = current_balance + (new_delta - old_delta)
        where id = new.account_id
          and user_id = new.user_id;
      end if;
    else
      if old_delta <> 0 then
        update public.accounts
        set current_balance = current_balance - old_delta
        where id = old.account_id
          and user_id = old.user_id;
      end if;

      if new_delta <> 0 then
        update public.accounts
        set current_balance = current_balance + new_delta
        where id = new.account_id
          and user_id = new.user_id;
      end if;
    end if;

    return new;
  end if;
end;
$$;

commit;
