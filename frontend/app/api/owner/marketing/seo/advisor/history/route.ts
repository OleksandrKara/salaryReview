import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// Past SEO analyses, newest first — a plain GET, inherits SecurityConfig's general OWNER+ADS_MANAGER
// read gate on /api/owner/marketing/** (unlike the sibling analyze POST, which is owner-only).
export const GET = () => forwardToBackend('/api/owner/marketing/seo/advisor/history', 'GET');
