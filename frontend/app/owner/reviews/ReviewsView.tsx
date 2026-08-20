'use client';

import { useMemo, useState } from 'react';
import type { ReviewsOverview } from '../../lib/types';

const UNASSIGNED_PROVIDER_ID = -1;

function fmtAvg(avg: number | null): string {
  return avg == null ? '—' : avg.toFixed(1);
}

function fmtDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

function RatingBadge({ rating }: { rating: number | null }) {
  if (rating == null) {
    return (
      <span className="inline-flex shrink-0 items-center rounded-full bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-500">
        No rating
      </span>
    );
  }
  const color = rating >= 4 ? 'bg-green-50 text-green-700' : rating === 3 ? 'bg-amber-50 text-amber-700' : 'bg-red-50 text-red-700';
  return (
    <span className={`inline-flex shrink-0 items-center gap-0.5 rounded-full px-2 py-0.5 text-xs font-semibold ${color}`}>
      {rating} ★
    </span>
  );
}

// Overall + per-provider average, then the full filterable review list. Data volume here is small
// (one salon's checkout replies) — fetched once server-side, filtered client-side, no pagination.
export default function ReviewsView({ overview }: { overview: ReviewsOverview }) {
  const [providerFilter, setProviderFilter] = useState<number | 'all'>('all');

  const filteredReviews = useMemo(() => {
    if (providerFilter === 'all') return overview.reviews;
    if (providerFilter === UNASSIGNED_PROVIDER_ID) return overview.reviews.filter((r) => r.providerId == null);
    return overview.reviews.filter((r) => r.providerId === providerFilter);
  }, [overview.reviews, providerFilter]);

  return (
    <div>
      {/* Overall summary */}
      <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-3">
        <div className="rounded-xl p-4 ring-1 ring-zinc-200">
          <div className="text-2xl font-semibold text-zinc-900">{fmtAvg(overview.averageRating)}</div>
          <div className="text-xs text-zinc-500">Average rating ({overview.ratedCount} rated)</div>
        </div>
        <div className="rounded-xl p-4 ring-1 ring-zinc-200">
          <div className="text-2xl font-semibold text-zinc-900">{overview.totalCount}</div>
          <div className="text-xs text-zinc-500">Total replies</div>
        </div>
        <div className="col-span-2 rounded-xl p-4 ring-1 ring-zinc-200 sm:col-span-1">
          <div className="text-2xl font-semibold text-zinc-900">{overview.totalCount - overview.ratedCount}</div>
          <div className="text-xs text-zinc-500">Replies with no number in them</div>
        </div>
      </div>

      {/* Per-provider table */}
      <h2 className="mb-3 text-sm font-semibold text-zinc-700">By provider</h2>
      {overview.byProvider.length === 0 ? (
        <p className="mb-6 rounded-lg px-4 py-4 text-sm text-zinc-400 ring-1 ring-zinc-200">No reviews yet.</p>
      ) : (
        <div className="mb-6 overflow-hidden rounded-xl ring-1 ring-zinc-200">
          <table className="w-full text-sm">
            <thead className="bg-zinc-50 text-left text-xs font-medium text-zinc-500">
              <tr>
                <th className="px-4 py-2">Provider</th>
                <th className="px-4 py-2 text-right">Avg rating</th>
                <th className="px-4 py-2 text-right">Rated</th>
                <th className="px-4 py-2 text-right">Unrated</th>
              </tr>
            </thead>
            <tbody>
              {overview.byProvider.map((p) => (
                <tr key={p.providerId} className="border-t border-zinc-100">
                  <td className="px-4 py-2 text-zinc-800">{p.providerName}</td>
                  <td className="px-4 py-2 text-right text-zinc-800">{fmtAvg(p.averageRating)}</td>
                  <td className="px-4 py-2 text-right text-zinc-500">{p.ratedCount}</td>
                  <td className="px-4 py-2 text-right text-zinc-500">{p.unratedCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Filter + review list */}
      <div className="mb-3 flex items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-zinc-700">All reviews</h2>
        <select
          value={providerFilter}
          onChange={(e) => setProviderFilter(e.target.value === 'all' ? 'all' : Number(e.target.value))}
          className="rounded border border-zinc-200 bg-white px-2 py-1 text-sm text-zinc-700"
        >
          <option value="all">All providers</option>
          {overview.byProvider.map((p) => (
            <option key={p.providerId} value={p.providerId}>
              {p.providerName}
            </option>
          ))}
        </select>
      </div>

      {filteredReviews.length === 0 ? (
        <p className="rounded-lg px-4 py-6 text-sm text-zinc-400 ring-1 ring-zinc-200">No reviews for this filter.</p>
      ) : (
        <ul className="space-y-2">
          {filteredReviews.map((r) => (
            <li key={r.messageId} className="rounded-xl p-4 ring-1 ring-zinc-200">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <RatingBadge rating={r.rating} />
                    <span className="text-xs font-medium text-zinc-600">{r.providerName ?? 'No technician on file'}</span>
                  </div>
                  <p className="mt-1.5 break-words text-sm text-zinc-800">{r.body}</p>
                  <p className="mt-1.5 text-xs text-zinc-400">{r.customerName ?? r.phoneNumber}</p>
                </div>
                <span className="shrink-0 whitespace-nowrap text-xs text-zinc-400">{fmtDate(r.createdAt)}</span>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
