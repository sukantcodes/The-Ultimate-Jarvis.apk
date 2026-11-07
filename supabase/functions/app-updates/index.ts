import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.45.4";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
});
const hex = (bytes: ArrayBuffer) =>
  [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");
const sha256 = async (value: string) =>
  hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));

function adminClient(): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("server_not_configured");
  return createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
}

// Match the existing owner-dashboard authorization model: its short-lived, password-verified
// dashboard session is mandatory. If OWNER_USER_IDS is configured, it is an additional allow-list.
function allowedOwner(userId: string): boolean {
  const raw = Deno.env.get("OWNER_USER_IDS")?.trim();
  if (!raw) return false;
  const allowedIds = raw.split(",").map((v) => v.trim().toLowerCase()).filter(Boolean);
  return allowedIds.length > 0 && allowedIds.includes(userId.toLowerCase());
}

Deno.serve(async (req: Request) => {
  if (req.method !== "GET" && req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  try {
    const admin = adminClient();
    if (req.method === "GET") {
      const url = new URL(req.url);
      const current = Number.parseInt(url.searchParams.get("version_code") ?? "", 10);
      const channel = url.searchParams.get("channel") ?? "stable";
      if (!Number.isSafeInteger(current) || current < 0 || !["stable", "beta"].includes(channel)) {
        return json({ error: "bad_request" }, 400);
      }
      const { data, error } = await admin.from("app_releases")
        .select("version_code,version_name,apk_url,sha256,min_supported_version_code,release_notes,published_at,channel")
        .eq("channel", channel).eq("is_active", true)
        .order("version_code", { ascending: false }).limit(1).maybeSingle();
      if (error) return json({ error: "release_lookup_failed" }, 503);
      if (!data || Number(data.version_code) <= current) {
        return json({ update_available: false, current_version_code: current });
      }
      return json({
        update_available: true,
        force_update: current < Number(data.min_supported_version_code),
        current_version_code: current,
        release: data,
      });
    }

    const authHeader = req.headers.get("authorization") ?? "";
    const tokenMatch = /^Bearer\s+(\S+)$/i.exec(authHeader);
    if (!tokenMatch) return json({ error: "unauthorized" }, 401);
    const { data: authData, error: authError } = await admin.auth.getUser(tokenMatch[1]);
    const user = authData.user;
    if (authError || !user) return json({ error: "unauthorized" }, 401);
    if (!allowedOwner(user.id)) return json({ error: "forbidden" }, 403);

    const dashboardToken = req.headers.get("x-dashboard-session") ?? "";
    if (dashboardToken.length < 32 || dashboardToken.length > 128) return json({ error: "session_invalid" }, 401);
    const tokenHash = await sha256(dashboardToken);
    const { data: session, error: sessionError } = await admin.from("dashboard_sessions")
      .select("id,user_id,expires_at,revoked_at").eq("token_hash", tokenHash).maybeSingle();
    if (sessionError || !session || session.revoked_at || session.user_id !== user.id ||
        new Date(session.expires_at).getTime() <= Date.now()) {
      return json({ error: "session_invalid" }, 401);
    }

    let body: Record<string, unknown>;
    try { body = await req.json(); } catch { return json({ error: "bad_request" }, 400); }
    const channel = body.channel === "beta" ? "beta" : body.channel === "stable" ? "stable" : "";
    const versionCode = Number(body.version_code);
    const minVersion = Number(body.min_supported_version_code ?? 1);
    const versionName = typeof body.version_name === "string" ? body.version_name.trim() : "";
    const apkPath = typeof body.apk_path === "string" ? body.apk_path.trim() : "";
    const digest = typeof body.sha256 === "string" ? body.sha256.trim() : "";
    const notes = typeof body.release_notes === "string" ? body.release_notes : "";
    const unsafePath = apkPath.split("/").some((part) => part === "." || part === "..");
    if (!channel || !Number.isSafeInteger(versionCode) || versionCode <= 0 ||
        !Number.isSafeInteger(minVersion) || minVersion <= 0 || minVersion > versionCode ||
        versionName.length < 1 || versionName.length > 40 ||
        apkPath.length < 5 || apkPath.length > 500 || unsafePath ||
        !/^[A-Za-z0-9._/-]+\.apk$/i.test(apkPath) ||
        !/^[A-Fa-f0-9]{64}$/.test(digest) || notes.length > 8000) {
      return json({ error: "invalid_release_metadata" }, 400);
    }

    const segments = apkPath.split("/");
    const filename = segments.pop()!;
    const directory = segments.join("/");
    const { data: objects, error: storageError } = await admin.storage.from("jarvis-releases")
      .list(directory, { search: filename, limit: 100 });
    if (storageError || !objects?.some((item) => item.name === filename)) {
      return json({ error: "apk_not_found_in_release_bucket" }, 422);
    }

    const encodedPath = apkPath.split("/").map(encodeURIComponent).join("/");
    const apkUrl = Deno.env.get("SUPABASE_URL")!.replace(/\/$/, "") +
      "/storage/v1/object/public/jarvis-releases/" + encodedPath;
    const { data: published, error: publishError } = await admin.rpc("publish_app_release", {
      p_channel: channel, p_version_code: versionCode, p_version_name: versionName,
      p_apk_path: apkPath, p_apk_url: apkUrl, p_sha256: digest.toUpperCase(),
      p_min_supported_version_code: minVersion, p_release_notes: notes,
    });
    if (publishError || !published) return json({ error: "publish_failed" }, 500);
    return json({ ok: true, release: published }, 201);
  } catch (_error) {
    return json({ error: "server_error" }, 500);
  }
});
