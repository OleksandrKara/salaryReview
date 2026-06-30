// Shows whether a synced item's Russian translation is in the vector store. Nothing for English-only
// items; green once the bilingual content is synced, amber while a translation awaits sync. Shared by
// the KB and SOP sync sections so both read identically.
export default function LangIndexBadge({ hasRu, synced }: { hasRu: boolean; synced: boolean }) {
  if (!hasRu) return null;
  return synced ? (
    <span className="rounded bg-green-50 px-1.5 py-0.5 text-[10px] text-green-700">EN + RU</span>
  ) : (
    <span className="rounded bg-amber-50 px-1.5 py-0.5 text-[10px] text-amber-700">RU pending</span>
  );
}
