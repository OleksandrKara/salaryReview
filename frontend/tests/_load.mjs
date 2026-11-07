import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const ts = createRequire(import.meta.url)('typescript');

export async function loadTs(rel) {
  const src = fs.readFileSync(new URL(rel, import.meta.url), 'utf8');
  const out = ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'ts-')), 'm.mjs');
  fs.writeFileSync(file, out);
  return import(pathToFileURL(file).href);
}
