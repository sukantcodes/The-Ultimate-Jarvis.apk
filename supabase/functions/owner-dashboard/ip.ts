const IPV4 = /^(\\d{1,3})(\\.\\d{1,3}){3}$/;

/** Returns a canonical IP string, or null for malformed IPv4/IPv6 input. */
export function normalizeIp(raw: string): string | null {
  const ip = raw.trim();
  if (!ip || ip.length > 45 || ip.includes("%")) return null;
  if (ip.includes(":")) {
    try {
      const host = new URL(`http://[${ip}]/`).hostname;
      return host.startsWith("[") && host.endsWith("]")
        ? host.slice(1, -1).toLowerCase()
        : null;
    } catch {
      return null;
    }
  }
  if (!IPV4.test(ip)) return null;
  const parts = ip.split(".");
  if (parts.length !== 4 || parts.some((part) => part.length === 0 || Number(part) > 255)) return null;
  return parts.map((part) => String(Number(part))).join(".");
}
