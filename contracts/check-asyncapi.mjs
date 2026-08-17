// Validate AsyncAPI documents with the official parser.
//
// This exists because `npx @asyncapi/cli validate` - the command the work package specifies -
// cannot be installed: every published version depends on @asyncapi/studio-ui@0.5.0, which is not
// on the npm registry. The failure is in the CLI's packaging, not in our contracts.
//
// @asyncapi/parser is the engine that CLI wraps, so this performs the same validation. validate.sh
// tries the CLI first and only falls back to this, which means the specified command resumes
// automatically once upstream is fixed.
//
// Requires @asyncapi/parser to be resolvable from this file's directory; validate.sh arranges that.

import { Parser } from '@asyncapi/parser';
import { readFileSync } from 'node:fs';

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error('usage: check-asyncapi.mjs <document.yaml> [...]');
  process.exit(2);
}

let failed = 0;

for (const file of files) {
  const { document, diagnostics } = await new Parser().parse(readFileSync(file, 'utf8'));
  const errors = diagnostics.filter((d) => d.severity === 0);
  const warnings = diagnostics.filter((d) => d.severity === 1);

  for (const d of [...errors, ...warnings]) {
    const where = d.path.length ? ` (${d.path.join('/')})` : '';
    console.log(`  ${d.severity === 0 ? 'ERROR' : 'warn '}  ${d.message}${where}`);
  }

  if (document) {
    console.log(`  asyncapi ${document.version()}  "${document.info().title()}"`);
    for (const op of document.operations().all()) {
      console.log(`    ${op.action().padEnd(7)} ${op.id()}  ->  ${op.channels().all().map((c) => c.address()).join(', ')}`);
    }
  }

  const ok = document && errors.length === 0;
  if (!ok) failed += 1;
  console.log(`  ${file}: ${ok ? 'VALID' : 'INVALID'} - ${errors.length} error(s), ${warnings.length} warning(s)\n`);
}

process.exit(failed === 0 ? 0 : 1);
