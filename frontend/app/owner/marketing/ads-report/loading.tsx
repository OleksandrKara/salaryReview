import { LoadingScreen } from '../../../components/Spinner';

// Shown automatically while ad spend/revenue data is bucketed by week or month from Square — the
// same slow-on-cold-cache tab as Analytics, so this tells the marketing team something's happening
// instead of the tab appearing to hang.
export default function Loading() {
  return <LoadingScreen label="Loading ads report…" />;
}
