import { LoadingScreen } from '../../components/Spinner';

// Shown automatically while conversations are fetched — covers clicking the messages icon,
// which can be slow as conversation volume grows (see MessagesNotifierIcon).
export default function Loading() {
  return <LoadingScreen label="Loading messages…" />;
}
