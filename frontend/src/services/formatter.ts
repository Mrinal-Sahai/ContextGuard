// ../services/formatter.ts
export function normalizeAIText(input?: any): string {
  if (input == null) return '';

  // If input is already a React element or an object, stringify it as JSON in a code block.
  if (typeof input !== 'string') {
    try {
      return '```json\n' + JSON.stringify(input, null, 2) + '\n```';
    } catch {
      return String(input);
    }
  }

  let s = input;

  // unify newlines
  s = s.replace(/\r\n/g, '\n').replace(/\r/g, '\n');

  // fix "- -" -> "- "
  s = s.replace(/(^|\n)\s*-\s*-\s*/g, '$1- ');

  // remove excessive blank lines
  s = s.replace(/\n{3,}/g, '\n\n');

  return s.trim();
}