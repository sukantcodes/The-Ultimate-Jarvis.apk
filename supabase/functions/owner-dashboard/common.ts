// Helpers shared by the JARVIS Edge Functions (identical copy lives in owner-dashboard/common.ts).
import { createClient, type SupabaseClient, type User } from "npm:@supabase/supabase-js@2.45.4";

export const json = (body: unknown, status = 200, extra: Record<string, string> = {}): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...extra },
  });

/** Service-role client. The key only ever exists in the function environment, never in the app. */
export function adminClient(): SupabaseClient {
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

/** Validates the caller's Supabase access token with the Auth server. Returns null when invalid. */
export async function authenticate(req: Request, admin: SupabaseClient): Promise<User | null> {
  const header = req.headers.get("authorization") ?? "";
  const m = /^Bearer\s+(\S+)$/i.exec(header);
  if (!m) return null;
  const { data, error } = await admin.auth.getUser(m[1]);
  return error || !data.user ? null : data.user;
}

export { normalizeIp } from "./ip.ts";
import { normalizeIp } from "./ip.ts";

/**
 * Public IP as seen by Supabase's edge, never a value supplied in the request body. Falls back to
 * null (not stored) when no valid address is present.
 */
export function clientIp(req: Request): string | null {
  const raw = req.headers.get("cf-connecting-ip") ??
    req.headers.get("x-forwarded-for")?.split(",")[0] ?? "";
  return normalizeIp(raw);
}
