import bcrypt from "npm:bcryptjs@2.4.3";
import { adminClient, authenticate, clientIp } from "./common.ts";
import { createHandler } from "./handler.ts";

const admin = adminClient();

const toHex = (buf: ArrayBuffer) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");

Deno.serve(createHandler({
  env: (n) => Deno.env.get(n),
  admin,
  authenticate: (req) => authenticate(req, admin),
  clientIp,
  verifyPassword: async (password, hash) => {
    try { return await bcrypt.compare(password, hash); } catch { return false; }
  },
  randomToken: () => {
    const b = crypto.getRandomValues(new Uint8Array(32));
    return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  },
  sha256Hex: async (v) => toHex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(v))),
  now: () => new Date(),
}));
