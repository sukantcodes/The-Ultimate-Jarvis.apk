// Run with: node --experimental-strip-types --test handler.test.ts   (Node 22+)
// Exercises the authorization logic against an in-memory fake of the database.
import test from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { createHandler } from "./handler.ts";

const USER = "11111111-1111-4111-8111-111111111111";
const OTHER = "22222222-2222-4222-8222-222222222222";
const TEST_PASSWORD = "unit-test-only-password"; // throwaway value, unrelated to the real owner password

function fakeDb(clock: { t: number }, authUserCount = 0) {
  const sessions: Record<string, any>[] = [];
  const authUsers = Array.from({ length: authUserCount }, (_, index) => ({
    id: `user-${String(index).padStart(5, "0")}`,
    email: `user${index}@example.test`,
    created_at: "2026-01-01T00:00:00Z",
    last_sign_in_at: null,
  }));
  const attempts = new Map<string, { n: number; lockedUntil: number }>();
  const settings = { session_ttl_seconds: 900, max_failed_attempts: 3, lockout_seconds: 600 };
  const chain = (table: string) => {
    const f: Record<string, unknown> = {};
    let op = "select"; let patch: any = null; let hashEq: string | null = null; let idEq: string | null = null;
    const self: any = {
      select: () => self, order: () => self, limit: () => self, range: () => self, or: () => self, gte: () => self,
      insert: (row: any) => { op = "insert"; if (table === "dashboard_sessions") sessions.push({ id: String(sessions.length + 1), revoked_at: null, ...row }); return Promise.resolve({ error: null }); },
      update: (p: any) => { op = "update"; patch = p; return self; },
      eq: (col: string, v: any) => { if (col === "token_hash") hashEq = v; if (col === "id") idEq = v; return self; },
      single: () => Promise.resolve({ data: table === "owner_settings" ? settings : null }),
      maybeSingle: () => Promise.resolve({ data: sessions.find((s) => s.token_hash === hashEq) ?? null }),
      then: (res: any) => {
        if (op === "update" && table === "dashboard_sessions") { const s = sessions.find((x) => x.id === idEq); if (s) Object.assign(s, patch); }
        return Promise.resolve({ data: [], count: 0, error: null }).then(res);
      },
    };
    void f; return self;
  };
  const rpc = (name: string, a: any) => {
    if (name === "owner_locked_until") {
      const until = Math.max(0, ...a.p_keys.map((k: string) => attempts.get(k)?.lockedUntil ?? 0));
      return Promise.resolve({ data: until > clock.t ? new Date(until).toISOString() : null });
    }
    if (name === "register_owner_failure") {
      for (const k of a.p_keys) {
        const cur = attempts.get(k) ?? { n: 0, lockedUntil: 0 }; cur.n++;
        if (cur.n >= a.p_max) cur.lockedUntil = clock.t + a.p_lockout_seconds * 1000;
        attempts.set(k, cur);
      }
      return Promise.resolve({ data: null });
    }
    if (name === "clear_owner_failures") { attempts.delete(a.p_key); return Promise.resolve({ data: null }); }
    if (name === "owner_summary") return Promise.resolve({ data: { total_accounts: 4 } });
    if (name === "owner_trends") return Promise.resolve({ data: { days: a.p_days, daily: [] } });
    return Promise.resolve({ data: null });
  };
  return {
    from: chain,
    rpc,
    sessions,
    auth: {
      admin: {
        listUsers: async ({ page, perPage }: { page: number; perPage: number }) => ({
          data: { users: authUsers.slice((page - 1) * perPage, page * perPage) },
          error: null,
        }),
      },
    },
  };
}

function setup(env: Record<string, string | undefined> = { OWNER_PASSWORD_HASH: "hash-of-test" }, authUserCount = 0) {
  const clock = { t: Date.parse("2026-11-04T10:00:00Z") };
  const db = fakeDb(clock, authUserCount);
  let authedUser: string | null = USER;
  const handler = createHandler({
    env: (n) => env[n], admin: db,
    authenticate: async () => (authedUser ? { id: authedUser } : null),
    clientIp: () => "203.0.113.9",
    verifyPassword: async (p) => p === TEST_PASSWORD,
    randomToken: () => randomBytes(32).toString("base64url"),
    sha256Hex: async (v) => createHash("sha256").update(v).digest("hex"),
    now: () => new Date(clock.t),
  });
  const call = (body: unknown, headers: Record<string, string> = {}) =>
    handler(new Request("https://x/f", { method: "POST", headers, body: JSON.stringify(body) }));
  return { call, clock, db, setUser: (u: string | null) => { authedUser = u; } };
}

test("no signed-in user -> 401 for every action", async () => {
  const t = setup(); t.setUser(null);
  for (const action of ["verify", "summary", "trends", "devices", "ips", "events", "logout"]) {
    assert.equal((await t.call({ action, password: TEST_PASSWORD })).status, 401);
  }
});

test("analytics without a dashboard session are refused", async () => {
  const t = setup();
  for (const action of ["summary", "trends", "devices", "ips", "events"]) {
    const r = await t.call({ action });
    assert.equal(r.status, 401); assert.equal((await r.json()).error, "session_invalid");
  }
  assert.equal((await t.call({ action: "summary" }, { "x-dashboard-session": "x".repeat(43) })).status, 401);
});

test("wrong password -> generic 401 with no hint, never leaks the secret", async () => {
  const t = setup();
  const r = await t.call({ action: "verify", password: "nope" });
  const text = await r.text();
  assert.equal(r.status, 401);
  assert.ok(!text.includes("hash-of-test") && !text.includes(TEST_PASSWORD));
});

test("unconfigured backend fails closed", async () => {
  const t = setup({});
  assert.equal((await t.call({ action: "verify", password: TEST_PASSWORD })).status, 503);
});

test("lockout after repeated failures, even for the correct password", async () => {
  const t = setup();
  for (let i = 0; i < 3; i++) assert.equal((await t.call({ action: "verify", password: "bad" })).status, 401);
  const locked = await t.call({ action: "verify", password: TEST_PASSWORD });
  assert.equal(locked.status, 429);
  t.clock.t += 601_000;
  assert.equal((await t.call({ action: "verify", password: TEST_PASSWORD })).status, 200);
});

test("correct password issues a session that opens the dashboard, then expires and can be revoked", async () => {
  const t = setup();
  const v = await t.call({ action: "verify", password: TEST_PASSWORD });
  assert.equal(v.status, 200);
  const { token } = await v.json();
  const h = { "x-dashboard-session": token };
  const s = await t.call({ action: "summary" }, h);
  assert.equal(s.status, 200);
  assert.equal((await s.json()).counts.total_accounts, 4);

  const tr = await t.call({ action: "trends", days: 30 }, h);
  assert.equal(tr.status, 200); assert.equal((await tr.json()).days, 30);
  assert.equal((await (await t.call({ action: "trends", days: 999 }, h)).json()).days, 14); // clamps to a known window
  t.clock.t += 899_000; assert.equal((await t.call({ action: "devices" }, h)).status, 200);
  t.clock.t += 2_000;   assert.equal((await t.call({ action: "devices" }, h)).status, 401); // expired

  const t2 = setup(); const tok = (await (await t2.call({ action: "verify", password: TEST_PASSWORD })).json()).token;
  assert.equal((await t2.call({ action: "logout" }, { "x-dashboard-session": tok })).status, 200);
  assert.equal((await t2.call({ action: "summary" }, { "x-dashboard-session": tok })).status, 401); // revoked
});

test("owner users pagination includes accounts beyond the first 1,000", async () => {
  const t = setup(undefined, 1205);
  const verified = await t.call({ action: "verify", password: TEST_PASSWORD });
  assert.equal(verified.status, 200);
  const { token } = await verified.json();
  const headers = { "x-dashboard-session": token };

  const first = await t.call({ action: "users", page: 0, page_size: 20 }, headers);
  const firstBody = await first.json();
  assert.equal(first.status, 200);
  assert.equal(firstBody.total, 1205);
  assert.equal(firstBody.items.length, 20);

  const last = await t.call({ action: "users", page: 60, page_size: 20 }, headers);
  const lastBody = await last.json();
  assert.equal(last.status, 200);
  assert.equal(lastBody.items.length, 5);
});

test("a session token is bound to the user who verified", async () => {
  const t = setup();
  const { token } = await (await t.call({ action: "verify", password: TEST_PASSWORD })).json();
  t.setUser(OTHER);
  assert.equal((await t.call({ action: "summary" }, { "x-dashboard-session": token })).status, 401);
});

test("invalid IPv4 and IPv6 targets are rejected before creating access blocks", async () => {
  const t = setup();
  const { token } = await (await t.call({ action: "verify", password: TEST_PASSWORD })).json();
  const headers = { "x-dashboard-session": token };
  for (const target_value of ["999.999.999.999", "::::", "2001:db8:::1", "192.0.2"]) {
    const response = await t.call({ action: "block", block_type: "ip", target_value }, headers);
    assert.equal(response.status, 400, `expected invalid IP ${target_value} to be rejected`);
  }
});

test("OWNER_USER_IDS restricts who may verify", async () => {
  const t = setup({ OWNER_PASSWORD_HASH: "h", OWNER_USER_IDS: OTHER });
  assert.equal((await t.call({ action: "verify", password: TEST_PASSWORD })).status, 401);
  t.setUser(OTHER);
  assert.equal((await t.call({ action: "verify", password: TEST_PASSWORD })).status, 200);
});
