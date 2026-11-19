// JARVIS Universal Device Network API.
// POST { action, ...args } with:
//   Authorization: Bearer <Supabase user access token>          (account authentication)
//   X-UDN-Device / X-UDN-Ts / X-UDN-Nonce / X-UDN-Sig            (device authentication, ECDSA P-256 over the body)
// Every state change runs through service_role-only SQL functions; clients hold no table privileges.
import type { SupabaseClient } from "npm:@supabase/supabase-js@2.45.4";
import { adminClient, authenticate, json } from "./common.ts";
import { CAPABILITIES, isKnownCapability, needsSourceConfirmation } from "./catalog.ts";
import {
  canonicalRequest, fingerprint, generatePairingCode, importPublicKey, normalizeCode, sha256Hex, verifySignature,
} from "./sigcrypto.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const NONCE = /^[A-Za-z0-9_-]{16,64}$/;
const PRIVATE_IPV4 = /^(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})$/;
const ONLINE_WINDOW_MS = 135_000; // three 45 s heartbeats: one missed beat never flips a device offline
const CLOCK_SKEW_S = 120;
const PAIRING_TTL_MS = 10 * 60_000;
const MAX_TTL_S = 600;

type Row = Record<string, any>;
class ApiError extends Error {
  constructor(public status: number, public code: string, public detail?: Row) { super(code); }
}
const fail = (status: number, code: string, detail?: Row): never => { throw new ApiError(status, code, detail); };
const str = (v: unknown, max: number): string | null =>
  typeof v === "string" && v.trim() ? v.trim().slice(0, max) : null;
const isOnline = (d: Row): boolean =>
  d.status === "authorized" && !!d.last_seen_at && Date.now() - Date.parse(d.last_seen_at) <= ONLINE_WINDOW_MS;

interface Ctx {
  admin: SupabaseClient;
  ownerId: string;
  device: Row | null; // null only for `register`
  body: Row;
  isParent: boolean;
  slot: number | null;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const admin = adminClient();
  try {
    const user = await authenticate(req, admin);
    if (!user) return json({ error: "unauthorized" }, 401);

    const raw = await req.text();
    if (raw.length > 32_768) fail(413, "payload_too_large");
    let body: Row;
    try { body = JSON.parse(raw); } catch { return fail(400, "bad_request"); }
    if (!body || typeof body !== "object" || Array.isArray(body)) fail(400, "bad_request");
    const action = str(body.action, 40) ?? fail(400, "bad_request");

    const ts = req.headers.get("x-udn-ts") ?? "";
    const nonce = req.headers.get("x-udn-nonce") ?? "";
    const sig = req.headers.get("x-udn-sig") ?? "";
    const deviceHeader = req.headers.get("x-udn-device") ?? "";
    if (!/^\d{9,12}$/.test(ts) || Math.abs(Date.now() / 1000 - Number(ts)) > CLOCK_SKEW_S) fail(401, "stale_request");
    if (!NONCE.test(nonce) || !sig) fail(401, "bad_signature");
    const bodyHash = await sha256Hex(raw);

    let device: Row | null = null;
    if (action === "register") {
      const pk = str(body.public_key, 200) ?? fail(400, "bad_request");
      if (!(await verifySignature(pk, canonicalRequest(action, "register", ts, nonce, bodyHash), sig))) fail(401, "bad_signature");
    } else {
      if (!UUID.test(deviceHeader)) fail(401, "bad_signature");
      const { data } = await admin.from("udn_devices").select("*").eq("id", deviceHeader).eq("owner_id", user.id).maybeSingle();
      device = data;
      if (!device) fail(401, "unknown_device");
      if (!(await verifySignature(device!.public_key, canonicalRequest(action, deviceHeader, ts, nonce, bodyHash), sig))) {
        fail(401, "bad_signature");
      }
      if (device!.status === "revoked") fail(403, "device_revoked");
      // Replay protection: a (device, nonce) pair is accepted exactly once inside the freshness window.
      const { data: fresh, error } = await admin.rpc("udn_register_nonce", { p_device: device!.id, p_nonce: nonce });
      if (error) fail(500, "server_error");
      if (!fresh) fail(409, "replayed_request");
    }

    const limitKey = `${user.id}:${device?.id ?? "register"}:${action === "send_command" ? "cmd" : action === "redeem_pairing" ? "pair" : "gen"}`;
    const [max, windowS] = action === "send_command" ? [30, 60] : action === "redeem_pairing" ? [8, 600] : action === "register" ? [10, 600] : [240, 60];
    const { data: allowed } = await admin.rpc("udn_rate_check", { p_key: limitKey, p_max: max, p_window_seconds: windowS });
    if (allowed === false) fail(429, "rate_limited");

    let isParent = false, slot: number | null = null;
    if (device) {
      const { data: pa } = await admin.from("udn_parent_assignments").select("slot").eq("owner_id", user.id).eq("device_id", device.id).maybeSingle();
      if (pa) { isParent = device.status === "authorized"; slot = pa.slot; }
    }
    const ctx: Ctx = { admin, ownerId: user.id, device, body, isParent, slot };

    const handler = HANDLERS[action];
    if (!handler) fail(400, "unknown_action");
    // Pending devices may only enrol; everything else needs an authorized device.
    if (device && device.status !== "authorized" && !PENDING_OK.has(action)) fail(403, "device_not_authorized");
    return json({ ok: true, ...(await handler!(ctx)) });
  } catch (e) {
    if (e instanceof ApiError) return json({ ok: false, error: e.code, ...(e.detail ?? {}) }, e.status);
    console.error("udn-api internal error", (e as Error)?.message);
    return json({ ok: false, error: "server_error" }, 500);
  }
});

const PENDING_OK = new Set(["me", "heartbeat", "claim_first_parent", "redeem_pairing", "update_capabilities"]);
const requireParent = (c: Ctx) => { if (!c.isParent) fail(403, "parent_required"); };
const rpc = async (c: Ctx, fn: string, args: Row): Promise<any> => {
  const { data, error } = await c.admin.rpc(fn, args);
  if (error) { console.error("udn rpc failed", fn, error.code); fail(500, "server_error"); }
  return data;
};

const HANDLERS: Record<string, (c: Ctx) => Promise<Row>> = {
  async register(c) {
    const b = c.body;
    const publicKey = str(b.public_key, 200) ?? fail(400, "bad_request");
    if (!(await importPublicKey(publicKey!))) fail(400, "invalid_public_key");
    const platform = b.platform === "windows" ? "windows" : b.platform === "android" ? "android" : fail(400, "bad_request");
    const name = str(b.name, 60) ?? fail(400, "bad_request");
    const fp = await fingerprint(publicKey!);
    const { data: existing } = await c.admin.from("udn_devices").select("id,status").eq("owner_id", c.ownerId).eq("key_fingerprint", fp).maybeSingle();
    if (existing?.status === "revoked") fail(403, "device_revoked", { hint: "regenerate_identity" });
    const fields = {
      platform, manufacturer: str(b.manufacturer, 60), model: str(b.model, 80),
      os_version: str(b.os_version, 40), app_version: str(b.app_version, 40),
    };
    let id: string, status: string;
    if (existing) {
      id = existing.id; status = existing.status;
      await c.admin.from("udn_devices").update(fields).eq("id", id);
    } else {
      const { data, error } = await c.admin.from("udn_devices")
        .insert({ owner_id: c.ownerId, name, public_key: publicKey, key_fingerprint: fp, ...fields })
        .select("id,status").single();
      if (error || !data) fail(500, "server_error");
      id = data!.id; status = data!.status;
      await rpc(c, "udn_audit", { p_owner: c.ownerId, p_actor: id, p_action: "device_registered", p_target: id, p_detail: { platform } });
    }
    const { count } = await c.admin.from("udn_parent_assignments").select("slot", { count: "exact", head: true }).eq("owner_id", c.ownerId);
    return { device_id: id, status, parents_exist: (count ?? 0) > 0 };
  },

  async me(c) {
    const { data: parents } = await c.admin.from("udn_parent_assignments").select("slot,device_id").eq("owner_id", c.ownerId);
    return {
      device: pickDevice(c.device!), slot: c.slot, is_parent: c.isParent,
      parents_exist: (parents?.length ?? 0) > 0, free_parent_slots: 2 - (parents?.length ?? 0),
    };
  },

  async claim_first_parent(c) {
    const r = await rpc(c, "udn_claim_first_parent", { p_owner: c.ownerId, p_device: c.device!.id });
    if (!r?.ok) fail(409, r?.error ?? "claim_failed");
    return { slot: r.slot };
  },

  async create_pairing(c) {
    requireParent(c);
    const kind = c.body.kind === "parent" ? "parent" : "child";
    if (kind === "parent") {
      const { count } = await c.admin.from("udn_parent_assignments").select("slot", { count: "exact", head: true }).eq("owner_id", c.ownerId);
      if ((count ?? 0) >= 2) fail(409, "no_free_slot");
    }
    const { data: ok } = await c.admin.rpc("udn_rate_check", { p_key: `${c.device!.id}:mkpair`, p_max: 6, p_window_seconds: 600 });
    if (ok === false) fail(429, "rate_limited");
    const code = generatePairingCode();
    const expires = new Date(Date.now() + PAIRING_TTL_MS).toISOString();
    const { error } = await c.admin.from("udn_pairing_requests").insert({
      owner_id: c.ownerId, kind, code_hash: await sha256Hex(`${c.ownerId}:${code}`), created_by: c.device!.id, expires_at: expires,
    });
    if (error) fail(500, "server_error");
    await rpc(c, "udn_audit", { p_owner: c.ownerId, p_actor: c.device!.id, p_action: "pairing_created", p_target: null, p_detail: { kind } });
    return { code, kind, expires_at: expires };
  },

  async redeem_pairing(c) {
    const code = normalizeCode(str(c.body.code, 16) ?? "");
    if (code.length !== 8) fail(400, "invalid_code");
    const r = await rpc(c, "udn_redeem_code", { p_owner: c.ownerId, p_device: c.device!.id, p_code_hash: await sha256Hex(`${c.ownerId}:${code}`) });
    if (!r?.ok) fail(r?.error === "invalid_code" ? 403 : 409, r?.error ?? "redeem_failed");
    return { kind: r.kind, slot: r.slot ?? null };
  },

  async decide_device(c) {
    requireParent(c);
    const target = UUID.test(c.body.target) ? c.body.target : fail(400, "bad_request");
    const decision = c.body.decision === "authorize" ? "authorize" : c.body.decision === "reject" ? "reject" : fail(400, "bad_request");
    const r = await rpc(c, "udn_decide_device", { p_owner: c.ownerId, p_actor: c.device!.id, p_target: target, p_decision: decision });
    if (!r?.ok) fail(409, r?.error ?? "decision_failed");
    return {};
  },

  async revoke(c) {
    const target = UUID.test(c.body.target) ? c.body.target : fail(400, "bad_request");
    const r = await rpc(c, "udn_revoke_device", { p_owner: c.ownerId, p_actor: c.device!.id, p_target: target });
    if (!r?.ok) fail(r?.error === "not_found" ? 404 : 403, r?.error ?? "revoke_failed");
    return {};
  },

  async rename(c) {
    requireParent(c);
    const target = UUID.test(c.body.target) ? c.body.target : fail(400, "bad_request");
    const name = str(c.body.name, 60) ?? fail(400, "bad_request");
    const { data } = await c.admin.from("udn_devices").update({ name }).eq("id", target).eq("owner_id", c.ownerId).neq("status", "revoked").select("id");
    if (!data?.length) fail(404, "not_found");
    await rpc(c, "udn_audit", { p_owner: c.ownerId, p_actor: c.device!.id, p_action: "device_renamed", p_target: target, p_detail: null });
    return {};
  },

  async heartbeat(c) {
    const b = c.body;
    const battery = Number.isInteger(b.battery_pct) && b.battery_pct >= 0 && b.battery_pct <= 100 ? b.battery_pct : null;
    const conn = ["wifi", "cellular", "ethernet", "other", "unknown"].includes(b.connection_type) ? b.connection_type : null;
    const lanHost = typeof b.lan_host === "string" && PRIVATE_IPV4.test(b.lan_host) ? b.lan_host : null;
    const lanPort = lanHost && Number.isInteger(b.lan_port) && b.lan_port >= 1024 && b.lan_port <= 65535 ? b.lan_port : null;
    await rpc(c, "udn_heartbeat", {
      p_device: c.device!.id, p_battery: battery, p_charging: typeof b.charging === "boolean" ? b.charging : null,
      p_conn: conn, p_lan_host: lanPort ? lanHost : null, p_lan_port: lanPort,
    });
    if (Math.random() < 0.02) await rpc(c, "udn_prune", {});
    let pending = 0;
    if (c.device!.status === "authorized") {
      await rpc(c, "udn_expire_commands", { p_owner: c.ownerId });
      const { count } = await c.admin.from("udn_command_queue").select("id", { count: "exact", head: true })
        .eq("target_device_id", c.device!.id).in("status", ["queued", "delivered"]).gt("expires_at", new Date().toISOString());
      pending = count ?? 0;
    }
    return { status: c.device!.status, pending_commands: pending, server_time: new Date().toISOString() };
  },

  async update_capabilities(c) {
    const list = Array.isArray(c.body.capabilities) ? c.body.capabilities.slice(0, 64) : fail(400, "bad_request");
    const rows: Row[] = [];
    for (const x of list as Row[]) {
      const cap = str(x?.capability, 60);
      if (!cap || !isKnownCapability(cap) || !CAPABILITIES[cap].platforms.includes(c.device!.platform)) continue;
      rows.push({
        device_id: c.device!.id, capability: cap, available: x.available === true, reason: str(x.reason, 200),
        os_permission: str(x.os_permission, 80),
        permission_granted: typeof x.permission_granted === "boolean" ? x.permission_granted : null,
        updated_at: new Date().toISOString(),
      });
    }
    if (rows.length) {
      const { error } = await c.admin.from("udn_device_capabilities").upsert(rows, { onConflict: "device_id,capability" });
      if (error) fail(500, "server_error");
    }
    const perms = Array.isArray(c.body.permissions) ? c.body.permissions.slice(0, 40) : [];
    const prow = (perms as Row[]).filter((p) => str(p?.permission, 80) && typeof p.granted === "boolean")
      .map((p) => ({ device_id: c.device!.id, permission: str(p.permission, 80), granted: p.granted, updated_at: new Date().toISOString() }));
    if (prow.length) await c.admin.from("udn_device_permissions").upsert(prow, { onConflict: "device_id,permission" });
    return { stored: rows.length };
  },

  async set_policy(c) {
    requireParent(c);
    const target = UUID.test(c.body.target) ? c.body.target : fail(400, "bad_request");
    const cap = str(c.body.capability, 60);
    const policy = ["default", "allow", "confirm", "deny"].includes(c.body.policy) ? c.body.policy : fail(400, "bad_request");
    if (!cap || !isKnownCapability(cap)) fail(400, "unknown_capability");
    const { data } = await c.admin.from("udn_device_capabilities").update({ policy }).eq("device_id", target).eq("capability", cap).select("device_id");
    if (!data?.length) fail(404, "not_found");
    await rpc(c, "udn_audit", { p_owner: c.ownerId, p_actor: c.device!.id, p_action: "policy_changed", p_target: target, p_detail: { capability: cap, policy } });
    return {};
  },

  async list_devices(c) {
    requireParent(c);
    const [devices, parents, caps, groups, members] = await Promise.all([
      c.admin.from("udn_devices").select("*").eq("owner_id", c.ownerId).order("created_at"),
      c.admin.from("udn_parent_assignments").select("slot,device_id").eq("owner_id", c.ownerId),
      c.admin.from("udn_device_capabilities").select("*"),
      c.admin.from("udn_device_groups").select("*").eq("owner_id", c.ownerId),
      c.admin.from("udn_device_group_members").select("*"),
    ]);
    const ids = new Set((devices.data ?? []).map((d: Row) => d.id));
    const slotOf = new Map((parents.data ?? []).map((p: Row) => [p.device_id, p.slot]));
    return {
      self_id: c.device!.id,
      devices: (devices.data ?? []).map((d: Row) => ({
        ...pickDevice(d), parent_slot: slotOf.get(d.id) ?? null, online: isOnline(d),
        capabilities: (caps.data ?? []).filter((x: Row) => x.device_id === d.id).map(pickCapability),
      })),
      groups: (groups.data ?? []).map((g: Row) => ({
        id: g.id, name: g.name,
        device_ids: (members.data ?? []).filter((m: Row) => m.group_id === g.id && ids.has(m.device_id)).map((m: Row) => m.device_id),
      })),
    };
  },

  async send_command(c) {
    requireParent(c);
    const b = c.body;
    const target = UUID.test(b.target) ? b.target : fail(400, "bad_request");
    const cap = str(b.capability, 60) ?? fail(400, "bad_request");
    if (!isKnownCapability(cap)) fail(400, "unknown_capability");
    const cmdNonce = typeof b.command_nonce === "string" && NONCE.test(b.command_nonce) ? b.command_nonce : fail(400, "bad_request");
    const params = b.params && typeof b.params === "object" && !Array.isArray(b.params) ? b.params : {};
    const ttl = Math.min(MAX_TTL_S, Math.max(10, Number.isInteger(b.ttl_seconds) ? b.ttl_seconds : 60));

    // Idempotency: a retried send with the same nonce returns the original command and never enqueues twice.
    const { data: dup } = await c.admin.from("udn_command_queue").select("id,status").eq("source_device_id", c.device!.id).eq("nonce", cmdNonce).maybeSingle();
    if (dup) return { command_id: dup.id, status: dup.status, duplicate: true };

    if (target === c.device!.id) fail(400, "self_target");
    const { data: tgt } = await c.admin.from("udn_devices").select("*").eq("id", target).eq("owner_id", c.ownerId).maybeSingle();
    if (!tgt) fail(404, "target_not_found");
    if (tgt!.status !== "authorized") fail(403, "target_not_authorized");
    const spec = CAPABILITIES[cap];
    if (!spec.platforms.includes(tgt!.platform)) fail(422, "capability_unsupported", { reason: `Not available on ${tgt!.platform}.` });
    if (!isOnline(tgt!)) fail(409, "target_offline", { last_seen_at: tgt!.last_seen_at });

    const { data: row } = await c.admin.from("udn_device_capabilities").select("*").eq("device_id", target).eq("capability", cap).maybeSingle();
    if (!row || !row.available) fail(422, "capability_unavailable", { reason: row?.reason ?? "The device has not reported this capability." });
    if (row!.policy === "deny") fail(403, "denied_by_policy");
    if (row!.permission_granted === false) fail(422, "permission_missing", { permission: row!.os_permission });

    const requiresConsent = spec.targetConsent || row!.policy === "confirm";
    if ((needsSourceConfirmation(cap) || row!.policy === "confirm") && b.confirmed !== true) {
      fail(412, "confirmation_required", { sensitivity: spec.sensitivity });
    }
    const now = Date.now();
    const { data: cmd, error } = await c.admin.from("udn_command_queue").insert({
      owner_id: c.ownerId, source_device_id: c.device!.id, target_device_id: target, capability: cap, params,
      nonce: cmdNonce, sensitivity: spec.sensitivity, requires_consent: requiresConsent,
      created_at: new Date(now).toISOString(), expires_at: new Date(now + ttl * 1000).toISOString(),
    }).select("id,status,expires_at").single();
    if (error || !cmd) {
      if (error?.code === "23505") { // lost a race with a concurrent retry of the same nonce
        const { data: again } = await c.admin.from("udn_command_queue").select("id,status").eq("source_device_id", c.device!.id).eq("nonce", cmdNonce).single();
        if (again) return { command_id: again.id, status: again.status, duplicate: true };
      }
      fail(500, "server_error");
    }
    await rpc(c, "udn_audit", {
      p_owner: c.ownerId, p_actor: c.device!.id, p_action: "command_sent", p_target: target,
      p_detail: { capability: cap, sensitivity: spec.sensitivity, command_id: cmd!.id },
    });
    return { command_id: cmd!.id, status: cmd!.status, expires_at: cmd!.expires_at, target_online: true, requires_consent: requiresConsent };
  },

  async poll_commands(c) {
    await rpc(c, "udn_expire_commands", { p_owner: c.ownerId });
    const rows = await rpc(c, "udn_claim_commands", { p_device: c.device!.id, p_limit: 5 });
    // Re-check the sender is still an authorized parent at delivery time (a revoked parent's queued commands are dropped).
    const { data: parents } = await c.admin.from("udn_parent_assignments").select("device_id").eq("owner_id", c.ownerId);
    const parentIds = new Set((parents ?? []).map((p: Row) => p.device_id));
    const out: Row[] = [];
    for (const r of (rows ?? []) as Row[]) {
      if (!parentIds.has(r.source_device_id)) {
        await rpc(c, "udn_report_result", { p_device: c.device!.id, p_command: r.id, p_status: "rejected", p_result: null, p_error_code: "source_not_parent", p_error_message: "Sender is no longer a parent device." });
        continue;
      }
      out.push({
        id: r.id, source_device_id: r.source_device_id, capability: r.capability, params: r.params, sensitivity: r.sensitivity,
        requires_consent: r.requires_consent, created_at: r.created_at, expires_at: r.expires_at, delivery_count: r.delivery_count,
      });
    }
    return { commands: out };
  },

  async ack(c) {
    const id = UUID.test(c.body.command_id) ? c.body.command_id : fail(400, "bad_request");
    return { acked: await rpc(c, "udn_ack_command", { p_device: c.device!.id, p_command: id }) };
  },

  async report_result(c) {
    const b = c.body;
    const id = UUID.test(b.command_id) ? b.command_id : fail(400, "bad_request");
    const status = ["running", "succeeded", "failed", "rejected"].includes(b.status) ? b.status : fail(400, "bad_request");
    const result = b.result && typeof b.result === "object" ? b.result : null;
    if (result && JSON.stringify(result).length > 12_000) fail(413, "result_too_large");
    const outcome = await rpc(c, "udn_report_result", {
      p_device: c.device!.id, p_command: id, p_status: status, p_result: result,
      p_error_code: str(b.error_code, 60), p_error_message: str(b.error_message, 300),
    });
    if (outcome === "not_found") fail(404, "not_found");
    return { outcome };
  },

  async command_status(c) {
    requireParent(c);
    const id = UUID.test(c.body.command_id) ? c.body.command_id : fail(400, "bad_request");
    await rpc(c, "udn_expire_commands", { p_owner: c.ownerId });
    const { data } = await c.admin.from("udn_command_queue").select("*").eq("id", id).eq("owner_id", c.ownerId).maybeSingle();
    if (!data) fail(404, "not_found");
    const { data: events } = await c.admin.from("udn_command_events").select("event,created_at,device_id").eq("command_id", id).order("id");
    return { command: pickCommand(data!), events: events ?? [] };
  },

  async list_commands(c) {
    requireParent(c);
    const limit = Math.min(100, Math.max(1, Number.isInteger(c.body.limit) ? c.body.limit : 30));
    let q = c.admin.from("udn_command_queue").select("*").eq("owner_id", c.ownerId).order("created_at", { ascending: false }).limit(limit);
    if (UUID.test(c.body.device_id)) q = q.or(`target_device_id.eq.${c.body.device_id},source_device_id.eq.${c.body.device_id}`);
    const { data } = await q;
    return { commands: (data ?? []).map(pickCommand) };
  },

  async list_sessions(c) {
    requireParent(c);
    const dev = UUID.test(c.body.device_id) ? c.body.device_id : fail(400, "bad_request");
    const { data: d } = await c.admin.from("udn_devices").select("id").eq("id", dev).eq("owner_id", c.ownerId).maybeSingle();
    if (!d) fail(404, "not_found");
    const { data } = await c.admin.from("udn_device_sessions").select("*").eq("device_id", dev).order("started_at", { ascending: false }).limit(30);
    return { sessions: data ?? [] };
  },

  async audit_list(c) {
    requireParent(c);
    const limit = Math.min(200, Math.max(1, Number.isInteger(c.body.limit) ? c.body.limit : 50));
    const { data } = await c.admin.from("udn_audit_logs").select("id,action,actor_device_id,target_device_id,detail,created_at")
      .eq("owner_id", c.ownerId).order("id", { ascending: false }).limit(limit);
    return { events: data ?? [] };
  },

  async create_group(c) {
    requireParent(c);
    const name = str(c.body.name, 40) ?? fail(400, "bad_request");
    const { data, error } = await c.admin.from("udn_device_groups").insert({ owner_id: c.ownerId, name }).select("id").single();
    if (error) fail(error.code === "23505" ? 409 : 500, error.code === "23505" ? "group_exists" : "server_error");
    return { group_id: data!.id };
  },

  async delete_group(c) {
    requireParent(c);
    const id = UUID.test(c.body.group_id) ? c.body.group_id : fail(400, "bad_request");
    await c.admin.from("udn_device_groups").delete().eq("id", id).eq("owner_id", c.ownerId);
    return {};
  },

  async group_members(c) {
    requireParent(c);
    const gid = UUID.test(c.body.group_id) ? c.body.group_id : fail(400, "bad_request");
    const did = UUID.test(c.body.device_id) ? c.body.device_id : fail(400, "bad_request");
    const [g, d] = await Promise.all([
      c.admin.from("udn_device_groups").select("id").eq("id", gid).eq("owner_id", c.ownerId).maybeSingle(),
      c.admin.from("udn_devices").select("id").eq("id", did).eq("owner_id", c.ownerId).maybeSingle(),
    ]);
    if (!g.data || !d.data) fail(404, "not_found");
    if (c.body.remove === true) await c.admin.from("udn_device_group_members").delete().eq("group_id", gid).eq("device_id", did);
    else await c.admin.from("udn_device_group_members").upsert({ group_id: gid, device_id: did });
    return {};
  },
};

function pickDevice(d: Row): Row {
  return {
    id: d.id, platform: d.platform, name: d.name, manufacturer: d.manufacturer, model: d.model, os_version: d.os_version,
    app_version: d.app_version, status: d.status, battery_pct: d.battery_pct, battery_charging: d.battery_charging,
    connection_type: d.connection_type, lan_host: d.lan_host, lan_port: d.lan_port, last_seen_at: d.last_seen_at,
    created_at: d.created_at, key_fingerprint: d.key_fingerprint,
  };
}
const pickCapability = (x: Row): Row => ({
  capability: x.capability, available: x.available, reason: x.reason, os_permission: x.os_permission,
  permission_granted: x.permission_granted, policy: x.policy,
});
const pickCommand = (r: Row): Row => ({
  id: r.id, source_device_id: r.source_device_id, target_device_id: r.target_device_id, capability: r.capability,
  sensitivity: r.sensitivity, status: r.status, created_at: r.created_at, expires_at: r.expires_at,
  acked_at: r.acked_at, completed_at: r.completed_at, result: r.result, error_code: r.error_code, error_message: r.error_message,
});
