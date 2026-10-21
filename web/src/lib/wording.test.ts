/*
 * GM-PRIV-024 (qa/antigravity/cases/14-privacy-security-policy.md): the dashboard never
 * calls Guardian Mode "monitoring" (or tracking, spying, surveillance) in text a helper
 * can see. Comments and identifiers don't count, so comments are stripped first.
 */
import { describe, expect, it } from 'vitest';

const sources = import.meta.glob(['../screens/**/*.tsx', '../components/**/*.tsx', './eventText.ts', './features.ts', './status.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`])\/\/.*$/gm, '$1');
}

const BANNED = /\b(monitor\w*|track(s|ed|ing)?|spy(ing)?|spies|surveil\w*)\b/i;

describe('GM-PRIV-024 helper-facing wording', () => {
  it('GM-PRIV-024 no "monitor", "track", "spy" or "surveillance" in dashboard screens, components or event text', () => {
    const files = Object.entries(sources).filter(([path]) => !path.includes('.test.'));
    expect(files.length).toBeGreaterThan(10);
    const hits = files.flatMap(([path, source]) =>
      withoutComments(source)
        .split('\n')
        .map((line, i) => ({ where: `${path}:${i + 1}`, line: line.trim() }))
        .filter(({ line }) => BANNED.test(line)),
    );
    expect(hits).toEqual([]);
  });
});
