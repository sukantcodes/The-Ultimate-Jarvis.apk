// POST { install_id, device_model, android_version, app_version, event: "sign_in" | "app_open" }
// Requires a valid signed-in Supabase session. Records the device and the server-observed IP.
// Guests (no account) are never recorded. Nothing else about the device is accepted.
import { adminClient, authenticate, clientIp, json } from "./common.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const clean = (v: unknown, max: number): string | null =>
  typeof v === "string" && v.trim() ? v.trim().slice(0, max) : null;

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const admin = adminClient();
  const user = await authenticate(req, admin);
  if (!user) return json({ error: "unauthorized" }, 401);

  let body: Record<string, unknown>;
  try { body = await req.json(); } catch { return json({ error: "bad_request" }, 400); }

  const installId = typeof body.install_id === "string" ? body.install_id : "";
  const event = body.event === "sign_in" || body.event === "app_open" || body.event === "check" ? body.event : null;
  if (!UUID.test(installId) || !event) return json({ error: "bad_request" }, 400);

  const ip = clientIp(req);
  const [userBlock, deviceBlock, ipBlock] = await Promise.all([
    admin.from("access_blocks").select("id").eq("block_type", "user").eq("target_value", user.id).maybeSingle(),
    admin.from("access_blocks").select("id").eq("block_type", "device").eq("target_value", installId).maybeSingle(),
    ip ? admin.from("access_blocks").select("id").eq("block_type", "ip").eq("target_value", ip).maybeSingle()
      : Promise.resolve({ data: null }),
  ]);
  if (userBlock.data || deviceBlock.data || ipBlock.data) {
    return json({ error: "access_blocked", message: "Access to JARVIS has been restricted. Contact the app owner." }, 403);
  }
  if (event === "check") return json({ ok: true, allowed: true });

  const { error } = await admin.rpc("record_device_seen", {
    p_install_id: installId,
    p_user_id: user.id,
    p_model: clean(body.device_model, 80),
    p_android: clean(body.android_version, 32),
    p_app_version: clean(body.app_version, 32),
    p_event: event,
    p_ip: ip,
  });
  if (error) return json({ error: "server_error" }, 500);
  return json({ ok: true });
});
