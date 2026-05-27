import { redirect } from 'next/navigation';

// The salary report is the app's home now; the legacy period-entry pages are being retired.
export default function Home() {
  redirect('/reports');
}
