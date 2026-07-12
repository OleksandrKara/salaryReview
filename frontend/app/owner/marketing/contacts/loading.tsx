import { LoadingScreen } from '../../../components/Spinner';

// Shown automatically while contacts + each one's Square appointment history are fetched — that
// Square lookup is a real several-second round trip on a cold cache, so this is what tells the
// owner something is happening instead of the tab appearing to hang.
export default function Loading() {
  return <LoadingScreen label="Loading contacts…" />;
}
