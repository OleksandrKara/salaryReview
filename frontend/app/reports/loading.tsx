import { LoadingScreen } from '../components/Spinner';

// Shown automatically while the report's Square data is fetched (covers landing here after login,
// month navigation, etc.).
export default function Loading() {
  return <LoadingScreen label="Loading report…" />;
}
