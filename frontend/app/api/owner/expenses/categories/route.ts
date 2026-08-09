import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/expenses/categories — every owner-editable expense category.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/expenses/categories', 'GET');
}

// POST /api/owner/expenses/categories — create a new category from a label.
export async function POST(req: Request): Promise<Response> {
  return forwardToBackend('/api/owner/expenses/categories', 'POST', await req.text());
}
