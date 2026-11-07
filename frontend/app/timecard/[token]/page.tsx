import ConfirmDay from './ConfirmDay';

export const metadata = { title: 'Confirm your hours', robots: { index: false, follow: false } };

export default async function TimecardLinkPage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  return (
    <main className="mx-auto max-w-md p-4 sm:p-8">
      <ConfirmDay token={token} />
    </main>
  );
}
