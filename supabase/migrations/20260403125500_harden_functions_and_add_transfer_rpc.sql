begin;

create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create or replace function public.accounts_set_current_from_opening()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  new.current_balance = coalesce(new.opening_balance, 0);
  return new;
end;
$$;

create or replace function public.apply_transaction_balance_delta()
returns trigger
language plpgsql
set search_path = public
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

create or replace function public.create_transfer(
  p_from_account_id uuid,
  p_to_account_id uuid,
  p_amount numeric,
  p_tax_amount numeric default 0,
  p_title text default 'Account Transfer',
  p_note text default '',
  p_occurred_on date default ((now() at time zone 'utc')::date),
  p_tag text default 'Transfer'
)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
  v_user_id uuid := auth.uid();
  v_account_count int;
  v_transfer_group_id uuid := gen_random_uuid();
  v_expense_id uuid;
  v_income_id uuid;
  v_tax_id uuid;
begin
  if v_user_id is null then
    raise exception 'Unauthorized';
  end if;

  if p_from_account_id = p_to_account_id then
    raise exception 'Source and destination accounts must be different';
  end if;

  if p_amount <= 0 then
    raise exception 'Transfer amount must be greater than zero';
  end if;

  if p_tax_amount < 0 then
    raise exception 'Tax amount cannot be negative';
  end if;

  select count(*) into v_account_count
  from public.accounts
  where user_id = v_user_id
    and id in (p_from_account_id, p_to_account_id);

  if v_account_count <> 2 then
    raise exception 'Invalid account selection';
  end if;

  insert into public.transactions (
    user_id,
    account_id,
    title,
    amount,
    transaction_type,
    tag,
    occurred_on,
    note,
    is_transfer,
    transfer_group_id
  )
  values (
    v_user_id,
    p_from_account_id,
    p_title,
    p_amount,
    'Expense',
    p_tag,
    p_occurred_on,
    p_note,
    true,
    v_transfer_group_id
  )
  returning id into v_expense_id;

  insert into public.transactions (
    user_id,
    account_id,
    title,
    amount,
    transaction_type,
    tag,
    occurred_on,
    note,
    is_transfer,
    transfer_group_id
  )
  values (
    v_user_id,
    p_to_account_id,
    p_title,
    p_amount,
    'Income',
    p_tag,
    p_occurred_on,
    p_note,
    true,
    v_transfer_group_id
  )
  returning id into v_income_id;

  if p_tax_amount > 0 then
    insert into public.transactions (
      user_id,
      account_id,
      title,
      amount,
      transaction_type,
      tag,
      occurred_on,
      note,
      is_transfer,
      transfer_group_id
    )
    values (
      v_user_id,
      p_from_account_id,
      p_title || ' - Tax',
      p_tax_amount,
      'Expense',
      'Transfer Fee',
      p_occurred_on,
      p_note,
      true,
      v_transfer_group_id
    )
    returning id into v_tax_id;
  end if;

  return jsonb_build_object(
    'transfer_group_id', v_transfer_group_id,
    'expense_transaction_id', v_expense_id,
    'income_transaction_id', v_income_id,
    'tax_transaction_id', v_tax_id
  );
end;
$$;

revoke all on function public.create_transfer(uuid, uuid, numeric, numeric, text, text, date, text) from public;
grant execute on function public.create_transfer(uuid, uuid, numeric, numeric, text, text, date, text) to authenticated;

commit;
