// @ts-nocheck
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

type JsonRecord = Record<string, unknown>;

const FUNCTION_SLUG = "auth-api";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const EMAIL_REDIRECT_TO = Deno.env.get("AUTH_EMAIL_REDIRECT_TO") ?? undefined;
const ALLOWED_ORIGIN = Deno.env.get("ALLOWED_ORIGIN") ?? "*";

function buildCorsHeaders(req: Request): HeadersInit {
  const origin = req.headers.get("origin") ?? "";
  const allowOrigin = ALLOWED_ORIGIN === "*" ? "*" : (origin === ALLOWED_ORIGIN ? origin : ALLOWED_ORIGIN);

  return {
    "Access-Control-Allow-Origin": allowOrigin,
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
    "Access-Control-Allow-Methods": "GET,POST,PATCH,OPTIONS",
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

function normalizeEmail(value: unknown): string {
  return typeof value === "string" ? value.trim().toLowerCase() : "";
}

function normalizeName(value: unknown): string | null {
  if (typeof value !== "string") {
    return null;
  }

  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

async function readJsonBody(req: Request): Promise<JsonRecord> {
  try {
    const body = await req.json();
    return typeof body === "object" && body !== null ? (body as JsonRecord) : {};
  } catch {
    return {};
  }
}

async function ensureProfile(
  serviceClient: ReturnType<typeof createClient>,
  userId: string,
  email: string,
  fullName: string | null
): Promise<void> {
  const payload: JsonRecord = {
    id: userId,
    email
  };

  if (fullName) {
    payload.full_name = fullName;
  }

  const { error } = await serviceClient
    .from("user_profiles")
    .upsert(payload, { onConflict: "id" });

  if (error) {
    throw new Error(`Failed to save profile: ${error.message}`);
  }
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: buildCorsHeaders(req) });
  }

  if (!SUPABASE_URL || !SUPABASE_ANON_KEY || !SUPABASE_SERVICE_ROLE_KEY) {
    return jsonResponse(req, 500, {
      error: "Supabase function environment is not configured"
    });
  }

  const route = getRoutePath(req, FUNCTION_SLUG);

  const anonClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    auth: {
      autoRefreshToken: false,
      persistSession: false
    }
  });

  const serviceClient = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: {
      autoRefreshToken: false,
      persistSession: false
    }
  });

  if (req.method === "POST" && route === "/signup") {
    const body = await readJsonBody(req);
    const email = normalizeEmail(body.email);
    const password = typeof body.password === "string" ? body.password : "";
    const fullName = normalizeName(body.full_name);

    if (!email || !email.includes("@")) {
      return jsonResponse(req, 400, { error: "Valid email is required" });
    }

    if (!password || password.length < 8) {
      return jsonResponse(req, 400, { error: "Password must be at least 8 characters" });
    }

    const { data, error } = await anonClient.auth.signUp({
      email,
      password,
      options: {
        data: fullName ? { full_name: fullName } : undefined,
        emailRedirectTo: EMAIL_REDIRECT_TO
      }
    });

    if (error) {
      return jsonResponse(req, 400, { error: error.message });
    }

    if (data.user?.id) {
      try {
        await ensureProfile(serviceClient, data.user.id, email, fullName);
      } catch (profileError) {
        return jsonResponse(req, 500, {
          error: profileError instanceof Error ? profileError.message : "Could not save profile"
        });
      }
    }

    return jsonResponse(req, 200, {
      message: "Signup created. Verify your email before logging in.",
      requires_email_verification: true,
      user_id: data.user?.id ?? null
    });
  }

  if (req.method === "POST" && route === "/login") {
    const body = await readJsonBody(req);
    const email = normalizeEmail(body.email);
    const password = typeof body.password === "string" ? body.password : "";

    if (!email || !password) {
      return jsonResponse(req, 400, { error: "Email and password are required" });
    }

    const { data, error } = await anonClient.auth.signInWithPassword({
      email,
      password
    });

    if (error || !data.session || !data.user) {
      return jsonResponse(req, 401, { error: "Invalid email or password" });
    }

    if (!data.user.email_confirmed_at) {
      await anonClient.auth.signOut();
      return jsonResponse(req, 403, {
        error: "Email not verified. Check your inbox before logging in."
      });
    }

    try {
      await ensureProfile(
        serviceClient,
        data.user.id,
        data.user.email ?? email,
        normalizeName(data.user.user_metadata?.full_name)
      );
    } catch (profileError) {
      return jsonResponse(req, 500, {
        error: profileError instanceof Error ? profileError.message : "Could not save profile"
      });
    }

    return jsonResponse(req, 200, {
      access_token: data.session.access_token,
      refresh_token: data.session.refresh_token,
      expires_at: data.session.expires_at,
      token_type: data.session.token_type,
      user: {
        id: data.user.id,
        email: data.user.email,
        full_name: normalizeName(data.user.user_metadata?.full_name)
      }
    });
  }

  if (req.method === "POST" && route === "/refresh") {
    const body = await readJsonBody(req);
    const refreshToken = typeof body.refresh_token === "string" ? body.refresh_token : "";

    if (!refreshToken) {
      return jsonResponse(req, 400, { error: "refresh_token is required" });
    }

    const { data, error } = await anonClient.auth.refreshSession({
      refresh_token: refreshToken
    });

    if (error || !data.session || !data.user) {
      return jsonResponse(req, 401, { error: "Invalid refresh token" });
    }

    if (!data.user.email_confirmed_at) {
      return jsonResponse(req, 403, {
        error: "Email not verified. Check your inbox before logging in."
      });
    }

    return jsonResponse(req, 200, {
      access_token: data.session.access_token,
      refresh_token: data.session.refresh_token,
      expires_at: data.session.expires_at,
      token_type: data.session.token_type,
      user: {
        id: data.user.id,
        email: data.user.email,
        full_name: normalizeName(data.user.user_metadata?.full_name)
      }
    });
  }

  if (req.method === "POST" && route === "/resend-verification") {
    const body = await readJsonBody(req);
    const email = normalizeEmail(body.email);

    if (email && email.includes("@")) {
      await anonClient.auth.resend({
        type: "signup",
        email,
        options: {
          emailRedirectTo: EMAIL_REDIRECT_TO
        }
      });
    }

    return jsonResponse(req, 200, {
      message: "If the account exists, a verification email has been sent."
    });
  }

  if (req.method === "POST" && route === "/forgot-password") {
    const body = await readJsonBody(req);
    const email = normalizeEmail(body.email);

    if (email && email.includes("@")) {
      await anonClient.auth.resetPasswordForEmail(email, {
        redirectTo: EMAIL_REDIRECT_TO
      });
    }

    return jsonResponse(req, 200, {
      message: "If the account exists, a reset email has been sent."
    });
  }

  return jsonResponse(req, 404, { error: "Route not found" });
});
