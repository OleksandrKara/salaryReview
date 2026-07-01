import PageHeader from '../../components/PageHeader';
import RagAdminClient from './RagAdminClient';

// Owner-only knowledge-corpus admin. Server shell (header + intro); the interactive body is a client
// component. The proxy gates /rag/admin to owners edge-side.
export default function RagAdminPage() {
  return (
    <main className="mx-auto max-w-3xl px-4 py-10">
      <PageHeader title="Knowledge base — admin" />
      <p className="mt-1 text-sm text-zinc-500">
        Upload SOPs, policies, or pricing docs (PDF, Markdown, or text). Uploads land pending; review,
        then approve to index. A per-chunk PII/relevance check runs before anything is embedded.
      </p>
      <RagAdminClient />
    </main>
  );
}
