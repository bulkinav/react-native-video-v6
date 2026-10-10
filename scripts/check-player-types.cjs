/** Проверяет публичный prop форка отдельно от деклараций приложения. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const ts = require(process.argv[2] || 'typescript');
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'rnv-types-'));
try {
  const fixture = path.join(directory, 'check.ts');
  const source = path.resolve(__dirname, '../src/types/video').replaceAll('\\', '/');
  fs.writeFileSync(fixture, `import type { ReactVideoProps, ReactVideoRenderLoaderProps } from '${source}';
const notice: ReactVideoProps['playbackNotice'] = 'Предупреждение';
// @ts-expect-error Предупреждение принимает текст, а не число.
const invalid: ReactVideoProps['playbackNotice'] = 42;
// @ts-expect-error Предупреждение не является prop загрузчика.
type LoaderNotice = ReactVideoRenderLoaderProps['playbackNotice'];
`);
  const program = ts.createProgram([fixture], {
    noEmit: true,
    skipLibCheck: true,
    esModuleInterop: true,
    target: ts.ScriptTarget.ES2022,
    moduleResolution: ts.ModuleResolutionKind.Node10,
  });
  // Проверяем fixture публичного API; полный native-модуль здесь не собирается.
  const errors = ts.getPreEmitDiagnostics(program).filter(item => item.file?.fileName === fixture);
  assert.equal(errors.length, 0, errors.map(item => ts.flattenDiagnosticMessageText(item.messageText, '\n')).join('\n'));
  process.stdout.write('Public playbackNotice type checks passed\n');
} finally {
  fs.rmSync(directory, { recursive: true, force: true });
}
