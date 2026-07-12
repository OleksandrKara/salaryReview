import { LoadingScreen } from '../../../components/Spinner';

// Shown automatically while ads-attributed revenue/conversion data is fetched from Square — this
// is the slowest of the marketing tabs on a cold cache, so this is what tells the owner something
// is happening instead of the tab appearing to hang.
export default function Loading() {
  return <LoadingScreen label="Loading analytics…" />;
}
