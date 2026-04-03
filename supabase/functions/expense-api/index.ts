// @ts-nocheck
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

type JsonRecord = Record<string, unknown>;

const FUNCTION_SLUG = "expense-api";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const ALLOWED_ORIGIN = Deno.env.get("ALLOWED_ORIGIN") ?? "*";

function buildCorsHeaders(req: Request): HeadersInit {
  const origin = req.headers.get("origin") ?? "";
  const allowOrigin = ALLOWED_ORIGIN === "*" ? "*" : (origin === ALLOWED_ORIGIN ? origin : ALLOWED_ORIGIN);

  return {
    "Access-Control-Allow-Origin": allowOrigin,
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
    "Access-Control-Allow-Methods": "GET,POST,PATCH,DELETE,OPTIONS",
    "Vary": "Origin",
    "Content-Type": "application/json"
  };
}

function jsonResponse(req: Request, status: number, payload: JsonRecord): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: buildCorsHeaders(req)
  });
}

function getRoutePath(req: Request, functionSlug: string): string {
  const parts = new URL(req.url).pathname.split("/").filter(Boolean);
  const slugIndex = parts.lastIndexOf(functionSlug);

  if (slugIndex >= 0) {
    const rest = parts.slice(slugIndex + 1);
    return rest.length === 0 ? "/" : `/${rest.join("/")}`;
  }

  return parts.length === 0 ? "/" : `/${parts.join("/")}`;
}

async function readJsonBody(req: Request): Promise<JsonRecord> {
  try {
    const body = await req.json();
    return typeof body === "object" && body !== null ? (body as JsonRecord) : {};
  } catch {
    return {};
  }
}

function normalizeName(value: unknown): string | null {
  if (typeof value !== "string") {
    return null;
  }

  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

function toPositiveNumber(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) {
    return null;
  }

  return value;
}

function toNonNegativeNumber(value: unknown, fallback = 0): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    return fallback;
  }

  return value;
}

function isValidTransactionType(value: unknown): value is "Income" | "Expense" {
  return value === "Income" || value === "Expense";
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: buildCorsHeaders(req) });
  }

  if (!SUPABASE_URL || !SUPABASE_ANON_KEY) {
    return jsonResponse(req, 500, {
      error: "Supabase function environment is not configured"
    });
  }

  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();

  if (!token) {
    return jsonResponse(req, 401, { error: "Missing bearer token" });
  }

  const userClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    auth: {
      autoRefreshToken: false,
      persistSession: false
    },
    global: {
      headers: {
        Authorization: `Bearer ${token}`
      }
    }
  });

  const { data: userData, error: userError } = await userClient.auth.getUser(token);

  if (userError || !userData.user) {
    return jsonResponse(req, 401, { error: "Invalid token" });
  }

  const user = userData.user;
  const routePath = getRoutePath(req, FUNCTION_SLUG);
  const routeParts = routePath.split("/").filter(Boolean);

  if (req.method === "GET" && routePath === "/profile") {
    const { data, error } = await userClient
      .from("user_profiles")
      .select("id,email,full_name,created_at,updated_at")
      .eq("id", user.id)
      .maybeSingle();

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    return jsonResponse(req, 200, { data });
  }

  if (req.method === "PATCH" && routePath === "/profile") {
    const body = await readJsonBody(req);
    const fullName = normalizeName(body.full_name);

    const { data, error } = await userClient
      .from("user_profiles")
      .upsert(
        {
          id: user.id,
          email: user.email,
          full_name: fullName
        },
        { onConflict: "id" }
      )
      .select("id,email,full_name,created_at,updated_at")
      .single();

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    return jsonResponse(req, 200, { data });
  }

  if (req.method === "GET" && routePath === "/accounts") {
    const { data, error } = await userClient
      .from("accounts")
      .select("id,name,opening_balance,current_balance,created_at,updated_at")
      .order("created_at", { ascending: false });

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    return jsonResponse(req, 200, { data });
  }

  if (req.method === "POST" && routePath === "/accounts") {
    const body = await readJsonBody(req);
    const name = normalizeName(body.name);
    const openingBalance = toNonNegativeNumber(body.opening_balance, 0);

    if (!name) {
      return jsonResponse(req, 400, { error: "Account name is required" });
    }

    const { data, error } = await userClient
      .from("accounts")
      .insert({
        user_id: user.id,
        name,
        opening_balance: openingBalance
      })
      .select("id,name,opening_balance,current_balance,created_at,updated_at")
      .single();

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    return jsonResponse(req, 201, { data });
  }

  if (req.method === "PATCH" && routeParts[0] === "accounts" && routeParts[1]) {
    const accountId = routeParts[1];
    const body = await readJsonBody(req);
    const name = normalizeName(body.name);

    if (!name) {
      return jsonResponse(req, 400, { error: "Account name is required" });
    }

    const { data, error } = await userClient
      .from("accounts")
      .update({ name })
      .eq("id", accountId)
      .eq("user_id", user.id)
      .select("id,name,opening_balance,current_balance,created_at,updated_at")
      .single();

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    return jsonResponse(req, 200, { data });
  }

  if (req.method === "DELETE" && routeParts[0] === "accounts" && routeParts[1]) {
    const accountId = routeParts[1];

    const { count, error: countError } = await userClient
      .from("transactions")
      .select("id", { count: "exact", head: true })
      .eq("account_id", accountId)
      .eq("user_id", user.id);

    if (countError) {
      return jsonResponse(req, 400, { error: countError.message });
    }

    if ((count ?? 0) > 0) {
      return jsonResponse(req, 409, {
        error: "Cannot delete account with existing transactions"
      });
    }

    const { error } = await userClient
      .from("accounts")
      .delete()
      .eq("id", accountId)
      .eq("user_id", user.id);

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    return jsonResponse(req, 200, { message: "Account deleted" });
  }

  if (req.method === "GET" && routePath === "/transactions") {
    const url = new URL(req.url);
    const accountId = url.searchParams.get("account_id");
    const transactionType = url.searchParams.get("transaction_type");
    const startDate = url.searchParams.get("start_date");
    const endDate = url.searchParams.get("end_date");
    const offset = Number.parseInt(url.searchParams.get("offset") ?? "0", 10);
    const rawLimit = Number.parseInt(url.searchParams.get("limit") ?? "100", 10);
    const limit = Number.isFinite(rawLimit) ? Math.min(Math.max(rawLimit, 1), 200) : 100;
    const safeOffset = Number.isFinite(offset) && offset >= 0 ? offset : 0;

    let query = userClient
      .from("transactions")
      .select("id,user_id,account_id,title,amount,transaction_type,tag,occurred_on,note,is_transfer,transfer_group_id,created_at,updated_at", { count: "exact" })
      .eq("user_id", user.id)
      .order("created_at", { ascending: false });

    if (accountId) {
      query = query.eq("account_id", accountId);
    }

    if (transactionType === "Income" || transactionType === "Expense") {
      query = query.eq("transaction_type", transactionType);
    }

    if (startDate) {
      query = query.gte("occurred_on", startDate);
    }

    if (endDate) {
      query = query.lte("occurred_on", endDate);
    }

    const { data, error, count } = await query.range(safeOffset, safeOffset + limit - 1);

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    return jsonResponse(req, 200, {
      data,
      pagination: {
        limit,
        offset: safeOffset,
        total: count ?? 0
      }
    });
  }

  if (req.method === "POST" && routePath === "/transactions") {
    const body = await readJsonBody(req);
    const accountId = typeof body.account_id === "string" ? body.account_id : "";
    const title = normalizeName(body.title);
    const amount = toPositiveNumber(body.amount);
    const transactionType = body.transaction_type;
    const tag = normalizeName(body.tag) ?? "General";
    const note = typeof body.note === "string" ? body.note.trim() : "";
    const occurredOn = typeof body.occurred_on === "string" ? body.occurred_on : undefined;
    const isTransfer = body.is_transfer === true;
    const transferGroupId = typeof body.transfer_group_id === "string" ? body.transfer_group_id : null;

    if (!accountId || !title || amount === null || !isValidTransactionType(transactionType)) {
      return jsonResponse(req, 400, {
        error: "account_id, title, amount, and a valid transaction_type are required"
      });
    }

    const payload: JsonRecord = {
      user_id: user.id,
      account_id: accountId,
      title,
      amount,
      transaction_type: transactionType,
      tag,
      note,
      is_transfer: isTransfer,
      transfer_group_id: transferGroupId
    };

    if (occurredOn) {
      payload.occurred_on = occurredOn;
    }

    const { data, error } = await userClient
      .from("transactions")
      .insert(payload)
      .select("id,user_id,account_id,title,amount,transaction_type,tag,occurred_on,note,is_transfer,transfer_group_id,created_at,updated_at")
      .single();

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    return jsonResponse(req, 201, { data });
  }

  if (req.method === "PATCH" && routeParts[0] === "transactions" && routeParts[1]) {
    const transactionId = routeParts[1];
    const body = await readJsonBody(req);

    const updates: JsonRecord = {};

    if (typeof body.account_id === "string") {
      updates.account_id = body.account_id;
    }

    const title = normalizeName(body.title);
    if (title) {
      updates.title = title;
    }

    if (body.amount !== undefined) {
      const amount = toPositiveNumber(body.amount);
      if (amount === null) {
        return jsonResponse(req, 400, { error: "amount must be a positive number" });
      }
      updates.amount = amount;
    }

    if (body.transaction_type !== undefined) {
      if (!isValidTransactionType(body.transaction_type)) {
        return jsonResponse(req, 400, { error: "transaction_type must be Income or Expense" });
      }
      updates.transaction_type = body.transaction_type;
    }

    const tag = normalizeName(body.tag);
    if (tag) {
      updates.tag = tag;
    }

    if (typeof body.note === "string") {
      updates.note = body.note.trim();
    }

    if (typeof body.occurred_on === "string") {
      updates.occurred_on = body.occurred_on;
    }

    if (typeof body.is_transfer === "boolean") {
      updates.is_transfer = body.is_transfer;
    }

    if (body.transfer_group_id === null || typeof body.transfer_group_id === "string") {
      updates.transfer_group_id = body.transfer_group_id;
    }

    if (Object.keys(updates).length === 0) {
      return jsonResponse(req, 400, { error: "No valid fields provided for update" });
    }

    const { data, error } = await userClient
      .from("transactions")
      .update(updates)
      .eq("id", transactionId)
      .eq("user_id", user.id)
      .select("id,user_id,account_id,title,amount,transaction_type,tag,occurred_on,note,is_transfer,transfer_group_id,created_at,updated_at")
      .single();

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    return jsonResponse(req, 200, { data });
  }

  if (req.method === "DELETE" && routeParts[0] === "transactions" && routeParts[1]) {
    const transactionId = routeParts[1];

    const { error } = await userClient
      .from("transactions")
      .delete()
      .eq("id", transactionId)
      .eq("user_id", user.id);

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    return jsonResponse(req, 200, { message: "Transaction deleted" });
  }

  if (req.method === "POST" && routePath === "/transfers") {
    const body = await readJsonBody(req);

    const fromAccountId = typeof body.from_account_id === "string" ? body.from_account_id : "";
    const toAccountId = typeof body.to_account_id === "string" ? body.to_account_id : "";
    const amount = toPositiveNumber(body.amount);
    const taxAmount = toNonNegativeNumber(body.tax_amount, 0);
    const title = normalizeName(body.title) ?? "Account Transfer";
    const note = typeof body.note === "string" ? body.note.trim() : "";
    const occurredOn = typeof body.occurred_on === "string" ? body.occurred_on : undefined;
    const tag = normalizeName(body.tag) ?? "Transfer";

    if (!fromAccountId || !toAccountId || amount === null) {
      return jsonResponse(req, 400, {
        error: "from_account_id, to_account_id and amount are required"
      });
    }

    const rpcArgs: JsonRecord = {
      p_from_account_id: fromAccountId,
      p_to_account_id: toAccountId,
      p_amount: amount,
      p_tax_amount: taxAmount,
      p_title: title,
      p_note: note,
      p_tag: tag
    };

    if (occurredOn) {
      rpcArgs.p_occurred_on = occurredOn;
    }

    const { data, error } = await userClient.rpc("create_transfer", rpcArgs);

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    return jsonResponse(req, 201, { data });
  }

  return jsonResponse(req, 404, { error: "Route not found" });
});
