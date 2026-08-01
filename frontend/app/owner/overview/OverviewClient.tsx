'use client';

import { useState } from 'react';
import type { OwnerOverviewData } from '../../lib/types';
import ExpenseEntryForm from './ExpenseEntryForm';
import GrowthTable from './GrowthTable';
import PeriodSummary from './PeriodSummary';
import RangePicker from './RangePicker';
import RevenueChart, { type Channel } from './RevenueChart';

export default function OverviewClient({ data }: { data: OwnerOverviewData }) {
  const [channel, setChannel] = useState<Channel>('all');

  return (
    <div data-testid="overview-root">
      {/* Range picker — sits in the open page, no card wrapper */}
      <div className="mb-4" data-testid="overview-range-picker">
        <RangePicker
          fromYear={data.fromYear}
          fromMonth={data.fromMonth}
          toYear={data.toYear}
          toMonth={data.toMonth}
        />
      </div>

      {/* Hero: period total + KPIs */}
      <div className="mb-4" data-testid="overview-period-summary">
        <PeriodSummary data={data} />
      </div>

      {/* Bar chart */}
      <div data-testid="overview-chart" className="mb-4 rounded-lg p-4 ring-1 ring-zinc-200 sm:p-5">
        <p className="mb-3 text-sm font-medium text-zinc-500">Monthly revenue</p>
        <RevenueChart
          months={data.months}
          channel={channel}
          onChannelChange={setChannel}
        />
      </div>

      {/* Growth table */}
      <GrowthTable months={data.months} />

      {/* Net revenue: business-expense ledger (materials/rent/utilities/other) */}
      <ExpenseEntryForm />
    </div>
  );
}
