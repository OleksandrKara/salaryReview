'use client';

import { useEffect } from 'react';

// Keeps the browser tab title in step with the page header ("CRM - AK Lux Nails"), so several tabs
// open on different businesses can be told apart.
export default function DocTitle({ title }: { title: string }) {
  useEffect(() => {
    document.title = title;
  }, [title]);
  return null;
}
