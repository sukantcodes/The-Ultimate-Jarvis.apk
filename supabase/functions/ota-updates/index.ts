import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.45.4";

// Read-only OTA feed for the JARVIS app. Returns lightweight metadata only (the APK itself is downloaded from
// GitHub Releases). Responses carry an ETag so a repeat check with If-None-Match costs a 304 with no body.

const DEFAULT_BRANCH = "Antigravity";
const BRANCH_RE = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,98}$/;
const COLUMNS =
  "branch,version_code,version_name,tag,asset_name,apk_url,size_bytes,sha256,cert_sha256,min_sdk," +
  "min_supported_version_code,release_notes,commit_sha,published_at";

const baseHeaders = { "Content-Type": "application/json" };

function admin(): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("server_not_configured");
  return createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
}

async function etagOf(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
  return '"' + hex.slice(0, 24) + '"';
}

Deno.serve(async (req: Request) => {
  if (req.method !== "GET") {
    return new Response(JSON.stringify({ error: "method_not_allowed" }), { status: 405, headers: baseHeaders });
  }
  try {
    const url = new URL(req.url);
    const requested = url.searchParams.get("branch") ?? DEFAULT_BRANCH;
    if (!BRANCH_RE.test(requested)) {
      return new Response(JSON.stringify({ error: "bad_request" }), { status: 400, headers: baseHeaders });
    }
    const db = admin();
    const { data: branches, error: branchError } = await db.from("ota_branches")
      .select("branch,is_default,description").eq("is_enabled", true)
      .order("is_default", { ascending: false }).order("branch", { ascending: true });
    if (branchError || !branches) {
      return new Response(JSON.stringify({ error: "feed_unavailable" }), { status: 503, headers: baseHeaders });
    }
    const defaultBranch = branches.find((b) => b.is_default)?.branch ?? DEFAULT_BRANCH;
    if (!branches.some((b) => b.branch === requested)) {
      return new Response(JSON.stringify({ error: "unknown_branch", default_branch: defaultBranch }), {
        status: 404, headers: { ...baseHeaders, "Cache-Control": "no-store" },
      });
    }
    const { data: releases, error: releaseError } = await db.from("ota_releases").select(COLUMNS)
      .eq("branch", requested).eq("status", "published")
      .order("version_code", { ascending: false }).limit(10);
    if (releaseError || !releases) {
      return new Response(JSON.stringify({ error: "feed_unavailable" }), { status: 503, headers: baseHeaders });
    }
    const body = JSON.stringify({
      branch: requested,
      default_branch: defaultBranch,
      branches,
      release: releases[0] ?? null,
      history: releases,
    });
    const etag = await etagOf(body);
    const cache = { "Cache-Control": "public, max-age=60", ETag: etag };
    if (req.headers.get("if-none-match") === etag) return new Response(null, { status: 304, headers: cache });
    return new Response(body, { status: 200, headers: { ...baseHeaders, ...cache } });
  } catch (_error) {
    return new Response(JSON.stringify({ error: "server_error" }), { status: 500, headers: baseHeaders });
  }
});
