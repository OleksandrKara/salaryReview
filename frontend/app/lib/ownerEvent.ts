export type OwnerSide = { name: string | null; kind: 'ASSIGNED' | 'DEFAULT' | 'TEAM' };
export type OwnerChange = { from: OwnerSide; to: OwnerSide; by: string; reset: boolean };

function side(v: unknown): OwnerSide {
  const o = (v ?? {}) as { name?: unknown; kind?: unknown };
  const kind = o.kind === 'ASSIGNED' || o.kind === 'DEFAULT' ? o.kind : 'TEAM';
  return { name: typeof o.name === 'string' ? o.name : null, kind };
}

/** Structured owner-change events are JSON; older events hold only the new owner's name (or "none"). */
export function parseOwnerChange(text: string | null | undefined): OwnerChange | null {
  if (!text || !text.startsWith('{')) return null;
  try {
    const j = JSON.parse(text);
    if (j?.v !== 1) return null;
    return { from: side(j.from), to: side(j.to), by: typeof j.by === 'string' ? j.by : 'System', reset: j.reset === true };
  } catch {
    return null;
  }
}

export type OwnerWords = {
  took: string; assigned: string; reset: string; team: string; defaultTag: string;
};

const fill = (tpl: string, v: Record<string, string>) => tpl.replace(/\{(\w+)\}/g, (_, k) => v[k] ?? '');

export function ownerChangeSentence(c: OwnerChange, w: OwnerWords): string {
  const show = (s: OwnerSide) => `${s.name ?? w.team}${s.kind === 'DEFAULT' ? w.defaultTag : ''}`;
  const v = { from: show(c.from), to: show(c.to), by: c.by };
  if (c.reset) return fill(w.reset, v);
  if (c.to.name && c.to.name === c.by) return fill(w.took, v);
  return fill(w.assigned, v);
}
