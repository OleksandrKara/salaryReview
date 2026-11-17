import type { IntakeSmsChip as Chip } from './types';

const PT = 'America/Los_Angeles';

function when(iso: string): string {
  return new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: PT });
}

export function intakeChipInfo(c: Chip): { label: string; className: string } {
  if (c.state === 'SIGNED') {
    const label = c.signedVia === 'STAFF_PAPER' ? 'Form on paper' : 'Form signed';
    return { label: c.signedAt ? `${label} ${when(c.signedAt)}` : label, className: 'bg-emerald-50 text-emerald-700' };
  }
  if (c.state === 'OPENED') {
    return { label: `Link opened${c.openedAt ? ' ' + when(c.openedAt) : ''}, not signed yet`, className: 'bg-amber-50 text-amber-700' };
  }
  return { label: 'Not opened, not signed yet', className: 'bg-zinc-100 text-zinc-600' };
}

export default function IntakeSmsChip({ chip, className = '' }: { chip: Chip | null | undefined; className?: string }) {
  if (!chip) return null;
  const info = intakeChipInfo(chip);
  return (
    <span
      data-testid="sms-intake-chip"
      data-state={chip.state}
      className={`inline-flex items-center whitespace-nowrap rounded-full px-2 py-0.5 text-[10px] font-medium ${info.className} ${className}`}
    >
      {info.label}
    </span>
  );
}
