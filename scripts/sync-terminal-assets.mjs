import { copyFile, mkdir } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const destination = resolve(root, "android/app/src/main/assets/terminal");
const files = [
  ["node_modules/@xterm/xterm/lib/xterm.js", "xterm.js"],
  ["node_modules/@xterm/xterm/css/xterm.css", "xterm.css"],
  ["node_modules/@xterm/xterm/LICENSE", "LICENSE.xterm"],
  ["node_modules/@xterm/addon-fit/lib/addon-fit.js", "addon-fit.js"],
  ["node_modules/@xterm/addon-fit/LICENSE", "LICENSE.addon-fit"],
];

await mkdir(destination, { recursive: true });
for (const [source, name] of files) {
  await copyFile(resolve(root, source), resolve(destination, name));
}
process.stdout.write(`Terminal assets synced to ${destination}\n`);
