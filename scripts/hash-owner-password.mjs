#!/usr/bin/env node
// Creates the bcrypt hash for the owner dashboard password.
//
//   cd scripts && npm install --no-save bcryptjs@2.4.3 && node hash-owner-password.mjs
//
// The password is typed at a hidden prompt, is never written to disk or logged, and only the hash is
// printed. Put that hash in the Supabase secret OWNER_PASSWORD_HASH. Do not commit the hash anywhere
// except the Supabase secret store, and do not paste the password into chats or tickets.
import bcrypt from "bcryptjs";
import readline from "node:readline";

function ask(question) {
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    rl._writeToOutput = (s) => { if (s.includes(question)) process.stdout.write(s); };
    rl.question(question, (answer) => { rl.close(); process.stdout.write("\n"); resolve(answer); });
  });
}

const first = await ask("Owner password: ");
const second = await ask("Repeat password: ");
if (first !== second) { console.error("Passwords do not match."); process.exit(1); }
if (first.length < 12 || first.length > 72) {
  console.error("Use 12-72 characters (bcrypt ignores anything past 72 bytes).");
  process.exit(1);
}
// Cost 12 is ~250 ms on a laptop and stays well inside the Edge Function CPU limit.
console.log("\nOWNER_PASSWORD_HASH=" + bcrypt.hashSync(first, 12));
