import { forwardToBackend } from '../../../../lib/proxyBackend';

export const GET = () => forwardToBackend('/api/owner/marketing/pages', 'GET');
