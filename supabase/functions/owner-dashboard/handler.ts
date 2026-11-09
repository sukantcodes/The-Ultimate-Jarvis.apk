import { normalizeIp } from "./ip.ts";

// Owner dashboard request handler. All authorization happens here, server-side.
//
//   POST { action: "verify", password }      -> { token, expires_at }   (needs a signed-in user)
//   POST { action: "summary" | "trends" | "devices" | "ips" | "events" | "logout", ... }
//        needs: Authorization: Bearer <user access token>  AND  x-dashboard-session: <token from verify>
//
// The owner password is never stored: only a bcrypt hash supplied via the OWNER_PASSWORD_HASH secret
// is compared. Dashboard session tokens are random, only their SHA-256 hash is stored, they expire
// quickly, and they are bound to the user that verified. Nothing here logs request bodies.

// deno-lint-ignore no-explicit-any
type Admin = any;
export interface AuthedUser { id: string }

export interface Deps {
  env: (name: string) => string | undefined;
  admin: Admin;
  authenticate: (req: Request) => Promise<AuthedUser | null>;
  clientIp: (req: Request) => string | null;
  verifyPassword: (password: string, hash: string) => Promise<boolean>;
  randomToken: () => string;
  sha256Hex: (value: string) => Promise<string>;
  now: () => Date;
}

const json = (body: unknown, status = 200, extra: Record<string, string> = {}): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...extra },
  });

const GENERIC_DENIED = { error: "invalid_credentials", message: "Access denied." };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SAFE_TERM = /^[A-Za-z0-9 ._-]{1,40}$/;

function pageParams(body: Record<string, unknown>) {
  const page = Math.max(0, Math.min(10_000, Number(body.page) || 0));
  const size = Math.max(1, Math.min(50, Number(body.page_size) || 20));
  return { from: page * size, to: page * size + size - 1, page, size };
}

export function createHandler(d: Deps) {
  const ownerIds = (): string[] | null => {
    const raw = d.env("OWNER_USER_IDS")?.trim();
    return raw ? raw.split(",").map((s) => s.trim().toLowerCase()).filter(Boolean) : null;
  };
  const isAllowedUser = (id: string) => {
    const ids = ownerIds();
    return ids === null || ids.includes(id.toLowerCase());
  };

  async function settings() {
    const { data } = await d.admin.from("owner_settings").select("*").eq("id", true).single();
    return {
      ttl: data?.session_ttl_seconds ?? 900,
      maxFailed: data?.max_failed_attempts ?? 5,
      lockout: data?.lockout_seconds ?? 900,
      ipRetentionDays: data?.ip_retention_days ?? 30,
      eventRetentionDays: data?.event_retention_days ?? 90,
    };
  }

  async function verify(req: Request, user: AuthedUser, body: Record<string, unknown>): Promise<Response> {
    const hash = d.env("OWNER_PASSWORD_HASH");
    if (!hash) return json({ error: "unavailable", message: "Dashboard is not configured." }, 503);

    const s = await settings();
    const ip = d.clientIp(req);
    const keys = [`u:${user.id}`, ...(ip ? [`ip:${ip}`] : [])];

    const { data: lockedUntil } = await d.admin.rpc("owner_locked_until", { p_keys: keys });
    if (lockedUntil) {
      const retry = Math.max(1, Math.ceil((new Date(lockedUntil).getTime() - d.now().getTime()) / 1000));
      return json({ error: "locked", message: "Too many attempts. Try again later.", retry_after: retry }, 429,
        { "Retry-After": String(retry) });
    }

    const password = typeof body.password === "string" ? body.password : "";
    // bcrypt only uses the first 72 bytes; refuse absurd inputs instead of hashing them.
    const ok = password.length > 0 && password.length <= 128 &&
      isAllowedUser(user.id) && await d.verifyPassword(password, hash);

    if (!ok) {
      await d.admin.rpc("register_owner_failure", {
        p_keys: keys, p_max: s.maxFailed, p_lockout_seconds: s.lockout,
      });
      return json(GENERIC_DENIED, 401);
    }

    await d.admin.rpc("clear_owner_failures", { p_key: `u:${user.id}` });
    const token = d.randomToken();
    const expires = new Date(d.now().getTime() + s.ttl * 1000);
    const { error } = await d.admin.from("dashboard_sessions").insert({
      token_hash: await d.sha256Hex(token), user_id: user.id, expires_at: expires.toISOString(),
    });
    if (error) return json({ error: "server_error" }, 500);
    return json({ token, expires_at: expires.toISOString(), ttl_seconds: s.ttl });
  }

  /** Returns the live session row for the presented token, or null. */
  async function requireSession(req: Request, user: AuthedUser) {
    const token = req.headers.get("x-dashboard-session") ?? "";
    if (token.length < 32 || token.length > 128 || !isAllowedUser(user.id)) return null;
    const { data } = await d.admin.from("dashboard_sessions").select("id,user_id,expires_at,revoked_at")
      .eq("token_hash", await d.sha256Hex(token)).maybeSingle();
    if (!data || data.revoked_at || data.user_id !== user.id) return null;
    if (new Date(data.expires_at).getTime() <= d.now().getTime()) return null;
    return data as { id: string; expires_at: string };
  }

  async function summary() {
    const [{ data: counts }, s, { data: recent }, { data: activity }] = await Promise.all([
      d.admin.rpc("owner_summary"),
      settings(),
      d.admin.from("app_events").select("id,user_id,created_at,app_devices(device_model)")
        .eq("event_type", "sign_in").order("created_at", { ascending: false }).limit(10),
      d.admin.from("app_events").select("id,event_type,user_id,created_at,app_devices(device_model)")
        .order("created_at", { ascending: false }).limit(10),
    ]);
    const total = Number(counts?.total_accounts ?? 0);
    return {
      counts,
      // JARVIS has no Pro entitlement backend yet, so Pro usage and conversion cannot be measured.
      // Every account is reported as Free until a real entitlement source exists.
      modes: { entitlement_backend: false, free: total, pro: null, conversion_rate: null,
        note: "No Pro entitlement backend exists; Pro analytics are not tracked." },
      presence: { currently_connected_available: false,
        note: "No heartbeat/presence mechanism; only last-seen times are available." },
      retention: { ip_days: s.ipRetentionDays, event_days: s.eventRetentionDays },
      recent_signins: recent ?? [],
      recent_activity: activity ?? [],
    };
  }

  async function devices(body: Record<string, unknown>) {
    const { from, to, page, size } = pageParams(body);
    let q = d.admin.from("app_devices").select(
      "id,install_id,user_id,device_model,android_version,app_version,first_seen_at,last_seen_at", { count: "exact" });
    const term = typeof body.search === "string" ? body.search.trim() : "";
    if (term) {
      if (UUID.test(term)) q = q.or(`install_id.eq.${term},user_id.eq.${term}`);
      else if (SAFE_TERM.test(term)) q = q.or(`device_model.ilike.%${term}%,app_version.ilike.%${term}%,android_version.ilike.%${term}%`);
      else return { items: [], total: 0, page, page_size: size };
    }
    const days = Number(body.active_within_days);
    if ([1, 7, 30].includes(days)) {
      q = q.gte("last_seen_at", new Date(d.now().getTime() - days * 86_400_000).toISOString());
    }
    const { data, count } = await q.order("last_seen_at", { ascending: false }).range(from, to);
    const nowMs = d.now().getTime();
    const items = (data ?? []).map((r: Record<string, string>) => ({
      ...r,
      // Only "recently active" can be claimed; there is no presence signal for "online".
      status: nowMs - new Date(r.last_seen_at).getTime() <= 86_400_000 ? "recently_active" : "inactive",
      mode: "free", mode_source: "no_entitlement_backend",
    }));
    return { items, total: count ?? 0, page, page_size: size };
  }

  async function ips(body: Record<string, unknown>) {
    const { from, to, page, size } = pageParams(body);
    let q = d.admin.from("ip_observations").select(
      "id,ip,user_id,device_id,first_seen_at,last_seen_at,request_count,app_devices(install_id,device_model)",
      { count: "exact" });
    const term = typeof body.search === "string" ? body.search.trim() : "";
    if (term) {
      if (UUID.test(term)) q = q.or(`user_id.eq.${term},device_id.eq.${term}`);
      else {
        const normalizedIp = normalizeIp(term);
        if (normalizedIp) q = q.eq("ip", normalizedIp);
        else return { items: [], total: 0, page, page_size: size };
      }
    }
    const { data, count } = await q.order("last_seen_at", { ascending: false }).range(from, to);
    return { items: data ?? [], total: count ?? 0, page, page_size: size,
      note: "IP addresses are observed server-side and are approximate network identifiers, not locations." };
  }


  async function blocks() {
    const { data, count } = await d.admin.from("access_blocks")
      .select("id,block_type,target_value,reason,created_at,created_by", { count: "exact" })
      .order("created_at", { ascending: false }).limit(200);
    if (!data) throw new Error("blocks");
    return { items: data, total: count ?? data.length };
  }

  async function mutateBlock(body: Record<string, unknown>, user: AuthedUser, remove: boolean) {
    const type = typeof body.block_type === "string" ? body.block_type : "";
    let target = typeof body.target_value === "string" ? body.target_value.trim() : "";
    if (!["user", "device", "ip"].includes(type) || target.length < 2 || target.length > 128)
      return json({ error: "bad_request" }, 400);
    if (type === "user" && !UUID.test(target)) return json({ error: "bad_request" }, 400);
    if (type === "device" && !UUID.test(target)) return json({ error: "bad_request" }, 400);
    if (type === "user" || type === "device") target = target.toLowerCase();
    if (type === "ip") {
      const normalizedIp = normalizeIp(target);
      if (!normalizedIp) return json({ error: "bad_request" }, 400);
      target = normalizedIp;
    }
    if (type === "user" && (target === user.id.toLowerCase() || ownerIds()?.includes(target))) return json({ error: "protected_owner" }, 403);
    if (remove) {
      const { error } = await d.admin.from("access_blocks").delete()
        .eq("block_type", type).eq("target_value", target);
      if (error) throw new Error("unblock");
    } else {
      const reason = typeof body.reason === "string" ? body.reason.trim().slice(0, 240) : "";
      const { error } = await d.admin.from("access_blocks").upsert({
        block_type: type, target_value: target, reason, created_by: user.id
      }, { onConflict: "block_type,target_value" });
      if (error) throw new Error("block");
    }
    return json({ ok: true });
  }

  async function fetchAllRows(buildQuery: () => any, label: string): Promise<Record<string, unknown>[]> {
    const rows: Record<string, unknown>[] = [];
    const pageSize = 1000;
    const maxPages = 100;
    for (let page = 0; page < maxPages; page++) {
      const { data, error } = await buildQuery().range(page * pageSize, (page + 1) * pageSize - 1);
      if (error) throw new Error(label);
      const batch = (data ?? []) as Record<string, unknown>[];
      rows.push(...batch);
      if (batch.length < pageSize) return rows;
    }
    throw new Error(label + "_pagination_limit");
  }

  async function users(body: Record<string, unknown>) {
    const { from, to, page, size } = pageParams(body);
    const [deviceRows, blockRows, ipRows] = await Promise.all([
      fetchAllRows(
        () => d.admin.from("app_devices")
          .select("user_id,install_id,device_model,android_version,app_version,last_seen_at")
          .order("last_seen_at", { ascending: false }),
        "users_devices"
      ),
      fetchAllRows(
        () => d.admin.from("access_blocks").select("block_type,target_value"),
        "users_blocks"
      ),
      fetchAllRows(
        () => d.admin.from("ip_observations").select("user_id,ip"),
        "users_ips"
      ),
    ]);

    // Supabase Auth Admin caps each page at 1,000 users. Fetch every page so
    // owner search and pagination never silently omit accounts after the first page.
    const authUsers: Record<string, unknown>[] = [];
    const authPageSize = 1000;
    const maxAuthPages = 100;
    let authComplete = false;
    for (let authPage = 1; authPage <= maxAuthPages; authPage++) {
      const { data, error } = await d.admin.auth.admin.listUsers({ page: authPage, perPage: authPageSize });
      if (error) throw new Error("users");
      const batch = (data?.users ?? []) as Record<string, unknown>[];
      authUsers.push(...batch);
      if (batch.length < authPageSize) {
        authComplete = true;
        break;
      }
    }
    if (!authComplete) throw new Error("users_pagination_limit");

    const items = authUsers.map((u: Record<string, unknown>) => {
      const id = String(u.id ?? "");
      const ownDevices = deviceRows.filter((x: Record<string, unknown>) => x.user_id === id);
      const ips = [...new Set((ipRows ?? []).filter((x: Record<string, unknown>) => x.user_id === id).map((x: Record<string, unknown>) => String(x.ip)))];
      return {
        user_id: id, email: typeof u.email === "string" ? u.email : null,
        created_at: u.created_at ?? null, last_sign_in_at: u.last_sign_in_at ?? null,
        devices: ownDevices, ips,
        blocked: blockRows.some((x: Record<string, unknown>) =>
          (x.block_type === "user" && x.target_value === id) ||
          (x.block_type === "device" && ownDevices.some((dev: Record<string, unknown>) => dev.install_id === x.target_value)) ||
          (x.block_type === "ip" && ips.includes(String(x.target_value))))
      };
    });
    const term = typeof body.search === "string" ? body.search.trim().toLowerCase() : "";
    const filtered = term ? items.filter((u: Record<string, unknown>) =>
      String(u.email ?? "").toLowerCase().includes(term) || String(u.user_id).toLowerCase().includes(term)) : items;
    return { items: filtered.slice(from, to + 1), total: filtered.length, page, page_size: size };
  }

  async function trends(body: Record<string, unknown>) {
    const days = [7, 14, 30].includes(Number(body.days)) ? Number(body.days) : 14;
    const { data, error } = await d.admin.rpc("owner_trends", { p_days: days });
    if (error || !data) throw new Error("trends");
    return data;
  }

  async function events(body: Record<string, unknown>) {
    const { from, to, page, size } = pageParams(body);
    let q = d.admin.from("app_events").select("id,event_type,user_id,created_at,app_devices(device_model)",
      { count: "exact" });
    if (body.event_type === "sign_in" || body.event_type === "app_open") q = q.eq("event_type", body.event_type);
    const { data, count } = await q.order("created_at", { ascending: false }).range(from, to);
    return { items: data ?? [], total: count ?? 0, page, page_size: size };
  }

  return async (req: Request): Promise<Response> => {
    if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
    try {
      const user = await d.authenticate(req);
      if (!user) return json({ error: "unauthorized" }, 401);

      let body: Record<string, unknown>;
      try { body = await req.json(); } catch { return json({ error: "bad_request" }, 400); }

      if (body.action === "verify") return await verify(req, user, body);

      const session = await requireSession(req, user);
      if (!session) return json({ error: "session_invalid", message: "Dashboard session is not valid." }, 401);

      switch (body.action) {
        case "summary": return json(await summary());
        case "devices": return json(await devices(body));
        case "ips": return json(await ips(body));
        case "events": return json(await events(body));
        case "blocks": return json(await blocks());
        case "block": return await mutateBlock(body, user, false);
        case "unblock": return await mutateBlock(body, user, true);
        case "users": return json(await users(body));
        case "trends": return json(await trends(body));
        case "logout":
          await d.admin.from("dashboard_sessions").update({ revoked_at: d.now().toISOString() }).eq("id", session.id);
          return json({ ok: true });
        default: return json({ error: "bad_request" }, 400);
      }
    } catch (_e) {
      // Deliberately no logging of request content.
      return json({ error: "server_error" }, 500);
    }
  };
}
