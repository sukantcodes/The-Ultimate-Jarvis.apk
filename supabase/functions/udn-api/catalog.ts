// Capability catalog shared (by value) with the Android app (UdnCapabilities.kt) and the Windows agent.
// The server is the authority on sensitivity: a client cannot downgrade a capability's risk level.

export type Sensitivity = "low" | "medium" | "high";

export interface CapabilitySpec {
  sensitivity: Sensitivity;
  /** The target device must show its own on-device consent prompt before executing. */
  targetConsent: boolean;
  platforms: ("android" | "windows")[];
}

export const CAPABILITIES: Record<string, CapabilitySpec> = {
  "device.info": { sensitivity: "low", targetConsent: false, platforms: ["android", "windows"] },
  "device.battery": { sensitivity: "low", targetConsent: false, platforms: ["android", "windows"] },
  "device.connectivity": { sensitivity: "low", targetConsent: false, platforms: ["android", "windows"] },
  "device.lock": { sensitivity: "medium", targetConsent: false, platforms: ["android", "windows"] },
  "app.launch": { sensitivity: "low", targetConsent: false, platforms: ["android", "windows"] },
  "module.open": { sensitivity: "low", targetConsent: false, platforms: ["android"] },
  "module.list": { sensitivity: "low", targetConsent: false, platforms: ["android"] },
  "notify.show": { sensitivity: "low", targetConsent: false, platforms: ["android", "windows"] },
  "settings.volume": { sensitivity: "medium", targetConsent: false, platforms: ["android"] },
  "settings.brightness": { sensitivity: "medium", targetConsent: false, platforms: ["android"] },
  "settings.flashlight": { sensitivity: "medium", targetConsent: false, platforms: ["android"] },
  "listening.stop": { sensitivity: "low", targetConsent: false, platforms: ["android"] },
  "chat.ask": { sensitivity: "medium", targetConsent: false, platforms: ["android"] },
  "routine.run": { sensitivity: "medium", targetConsent: false, platforms: ["android"] },
  "location.get": { sensitivity: "high", targetConsent: true, platforms: ["android"] },
  "camera.open": { sensitivity: "high", targetConsent: true, platforms: ["android"] },
};

export const isKnownCapability = (c: string): boolean => Object.prototype.hasOwnProperty.call(CAPABILITIES, c);

/** Medium/high commands need the sending parent to have confirmed them (voice or tap) on the source device. */
export const needsSourceConfirmation = (c: string): boolean => CAPABILITIES[c]?.sensitivity !== "low";
