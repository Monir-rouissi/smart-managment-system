export const SEARCH_MODES = ['HYBRID', 'KEYWORD', 'SEMANTIC'] as const;

export type SearchMode = (typeof SEARCH_MODES)[number];

/** Which retrieval path produced a hit. Only HYBRID can return BOTH. */
export type SearchMatch = 'KEYWORD' | 'SEMANTIC' | 'BOTH';

export interface SearchHit {
  chunkId: string;
  documentId: string;
  documentName: string;
  projectId: string | null;
  projectName: string | null;
  /** "p. 3" or "p. 3-4"; null for formats with no page structure. */
  pageOrSection: string | null;
  /** Highlights are marked with [[HL]] / [[/HL]] — never HTML. See splitSnippet. */
  snippet: string;
  score: number;
  match: SearchMatch;
}

export interface SnippetPart {
  text: string;
  marked: boolean;
}

const OPEN = '[[HL]]';
const CLOSE = '[[/HL]]';

/**
 * Turns a snippet into plain text parts.
 *
 * The chunk text comes from a file somebody uploaded, so it is never rendered as
 * HTML: the server emits inert markers and the template interpolates each part,
 * which leaves no injection surface at all and no dependency on what Angular's
 * sanitiser happens to allow through.
 */
export function splitSnippet(snippet: string): SnippetPart[] {
  const parts: SnippetPart[] = [];
  let rest = snippet ?? '';

  while (rest.length > 0) {
    const start = rest.indexOf(OPEN);
    if (start < 0) {
      parts.push({ text: rest, marked: false });
      break;
    }
    if (start > 0) {
      parts.push({ text: rest.slice(0, start), marked: false });
    }
    const afterOpen = rest.slice(start + OPEN.length);
    const end = afterOpen.indexOf(CLOSE);
    if (end < 0) {
      // Unbalanced marker: show the remainder as plain text rather than dropping it.
      parts.push({ text: afterOpen, marked: false });
      break;
    }
    parts.push({ text: afterOpen.slice(0, end), marked: true });
    rest = afterOpen.slice(end + CLOSE.length);
  }
  return parts.filter((p) => p.text.length > 0);
}
