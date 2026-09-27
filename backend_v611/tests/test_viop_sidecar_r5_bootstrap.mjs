import { writeFile } from 'node:fs/promises';

globalThis.Bun = {
  async write(path, data) { await writeFile(path, data); },
  serve(options) {
    if (!options || typeof options.fetch !== 'function') throw new Error('BUN_SERVE_FETCH_MISSING');
    return { port: Number(options.port || 8080) };
  },
};

await import('../viop_sidecar_r5_bootstrap_v2.mjs');
console.log('V611_R5_BOOTSTRAP_PATCH_INVARIANTS=PASS');
console.log('V611_R5_RUNTIME_STARTUP_WITHOUT_PROVIDER=PASS');
