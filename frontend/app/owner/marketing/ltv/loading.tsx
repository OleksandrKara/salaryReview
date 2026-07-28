import { LoadingScreen } from '../../../components/Spinner';

// Shown automatically while lifetime-value data is aggregated across every month of a page's
// history from Square — potentially a wider sweep than any single Ads Report period.
export default function Loading() {
  return <LoadingScreen label="Loading lifetime value…" />;
}
