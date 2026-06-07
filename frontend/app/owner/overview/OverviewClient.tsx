'use client';

import { useState } from 'react';
import type { OwnerOverviewData } from '../../lib/types';
import GrowthTable from './GrowthTable';
import PeriodSummary from './PeriodSummary';
import RangePicker from './RangePicker';
import RevenueChart, { type Channel } from './RevenueChart';

export default function OverviewClient({ data }: { data: OwnerOverviewData }) {
  const [channel, setChannel] = useState<Channel>('all');

  return (
    <div>
      {/* Range picker — sits in the open page, no card wrapper */}
      <div className="mb-4">
        <RangePicker
          fromYear={data.fromYear}
          fromMonth={data.fromMonth}
          toYear={data.toYear}
          toMonth={data.toMonth}
        />
      </div>

      {/* Hero: period total + KPIs */}
      <div className="mb-4">
        <PeriodSummary data={data} />
      </div>

      {/* Bar chart */}
      <div className="mb-4 rounded-lg p-4 ring-1 ring-zinc-200 sm:p-5">
        <p className="mb-3 text-sm font-medium text-zinc-500">Monthly revenue</p>
        <RevenueChart
          months={data.months}
          channel={channel}
          onChannelChange={setChannel}
        />
      </div>

      {/* Growth table */}
      <GrowthTable months={data.months} />
    </div>
  );
}
