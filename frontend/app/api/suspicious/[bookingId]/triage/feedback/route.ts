import { forwardToBackend } from '../../../../../lib/proxyBackend';

// POST /api/suspicious/{bookingId}/triage/feedback
// Body: { helpful: boolean, correctedClassification: 'LIKELY_LEGIT'|'NEEDS_REVIEW'|'LIKELY_FRAUD'|null }
// Owner records thumbs-up / thumbs-down on a triage card. Backend updates the row and ships a
// LangSmith feedback event linked to the original trace.
export async function POST(req: Request, ctx: { params: Promise<{ bookingId: string }> }): Promise<Response> {
  const { bookingId } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(
    `/api/suspicious/${encodeURIComponent(bookingId)}/triage/feedback`,
    'POST',
    body || '{}',
  );
}
