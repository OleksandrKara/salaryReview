import { forwardToBackend } from '../../../lib/proxyBackend';

// Expense-entry create + list — edit/delete of an existing row live in ./[id]/route.ts. OWNER-only
// (see SecurityConfig's /api/owner/** catch-all).
export async function POST(req: Request): Promise<Response> {
  return forwardToBackend('/api/owner/expenses', 'POST', await req.text());
}

export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/expenses', 'GET');
}
