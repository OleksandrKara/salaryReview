'use client';

import { useEffect, useRef, useState } from 'react';
import { api } from '../../lib/api';
import type { SmsConversationDto, SmsMessageDto } from '../../lib/types';

function formatPhone(phone: string): string {
  const digits = phone.replace(/\D/g, '');
  if (digits.length === 11 && digits.startsWith('1')) {
    return `(${digits.slice(1, 4)}) ${digits.slice(4, 7)}-${digits.slice(7)}`;
  }
  if (digits.length === 10) {
    return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`;
  }
  return phone;
}

function formatListTime(iso: string): string {
  return new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

function formatBubbleTime(iso: string): string {
  return new Date(iso).toLocaleString('en-US', { hour: 'numeric', minute: '2-digit' });
}

export default function MessagesView({ initialConversations }: { initialConversations: SmsConversationDto[] }) {
  const [conversations, setConversations] = useState(initialConversations);
  const [selectedPhone, setSelectedPhone] = useState<string | null>(null);
  const [thread, setThread] = useState<SmsMessageDto[]>([]);
  const [threadLoading, setThreadLoading] = useState(false);
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!selectedPhone) return;
    let cancelled = false;
    // Deferred via setTimeout (same convention as SmsActivityLog's debounced fetch) so the
    // setThreadLoading(true) call happens inside a callback rather than synchronously in the
    // effect body.
    const handle = setTimeout(() => {
      setThreadLoading(true);
      api.getSmsThread(selectedPhone)
        .then((data) => {
          if (!cancelled) setThread(data);
        })
        .finally(() => {
          if (!cancelled) setThreadLoading(false);
        });
    }, 0);
    return () => {
      cancelled = true;
      clearTimeout(handle);
    };
  }, [selectedPhone]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [thread]);

  function openThread(phoneNumber: string) {
    setSelectedPhone(phoneNumber);
    // Optimistic — the unread badge for this contact clears the moment they open it, same
    // instant-feedback convention as SmsActivityLog's mark-read-on-click.
    setConversations((prev) => prev.map((c) => (c.phoneNumber === phoneNumber ? { ...c, unreadCount: 0 } : c)));
  }

  async function sendReply() {
    const body = draft.trim();
    if (!body || !selectedPhone || sending) return;
    setSending(true);
    try {
      const result = await api.sendSmsReply(selectedPhone, body);
      if (result.sent) {
        setDraft('');
        const [freshThread, freshConversations] = await Promise.all([
          api.getSmsThread(selectedPhone),
          api.listSmsConversations(),
        ]);
        setThread(freshThread);
        setConversations(freshConversations);
      }
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="flex h-[70vh] min-h-[420px] overflow-hidden rounded-lg ring-1 ring-zinc-200">
      {/* Contact list — full width on mobile until a thread is opened, fixed sidebar on desktop. */}
      <div className={`w-full shrink-0 overflow-y-auto border-r border-zinc-200 sm:block sm:w-72 ${selectedPhone ? 'hidden sm:block' : ''}`}>
        {conversations.length === 0 ? (
          <div className="p-6 text-center text-sm text-zinc-500">No conversations yet.</div>
        ) : (
          conversations.map((c) => (
            <button
              key={c.phoneNumber}
              type="button"
              onClick={() => openThread(c.phoneNumber)}
              className={`flex w-full flex-col gap-0.5 border-b border-zinc-100 px-4 py-3 text-left hover:bg-zinc-50 ${
                c.phoneNumber === selectedPhone ? 'bg-sky-50' : ''
              }`}
            >
              <div className="flex items-center justify-between gap-2">
                <span className={`tabular-nums ${c.unreadCount > 0 ? 'font-semibold text-zinc-900' : 'font-medium text-zinc-700'}`}>
                  {formatPhone(c.phoneNumber)}
                </span>
                <span className="shrink-0 text-xs tabular-nums text-zinc-400">{formatListTime(c.lastMessageAt)}</span>
              </div>
              <div className="flex items-center justify-between gap-2">
                <span className={`truncate text-sm ${c.unreadCount > 0 ? 'font-medium text-zinc-900' : 'text-zinc-500'}`}>
                  {c.lastMessageDirection === 'OUTBOUND' ? 'You: ' : ''}
                  {c.lastMessageBody}
                </span>
                {c.unreadCount > 0 && (
                  <span className="flex h-[18px] min-w-[18px] shrink-0 items-center justify-center rounded-full bg-sky-600 px-1 text-[10px] font-semibold leading-none text-white">
                    {c.unreadCount > 99 ? '99+' : c.unreadCount}
                  </span>
                )}
              </div>
            </button>
          ))
        )}
      </div>

      {/* Thread — full width on mobile once opened, fills remaining space on desktop. */}
      <div className={`flex min-w-0 flex-1 flex-col ${selectedPhone ? 'flex' : 'hidden sm:flex'}`}>
        {!selectedPhone ? (
          <div className="flex flex-1 items-center justify-center text-sm text-zinc-400">
            Select a conversation
          </div>
        ) : (
          <>
            <div className="flex items-center gap-2 border-b border-zinc-200 px-4 py-3">
              <button
                type="button"
                onClick={() => setSelectedPhone(null)}
                aria-label="Back to conversations"
                className="text-zinc-400 hover:text-zinc-600 sm:hidden"
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                  <path d="m15 18-6-6 6-6" />
                </svg>
              </button>
              <span className="font-medium text-zinc-900">{formatPhone(selectedPhone)}</span>
            </div>

            <div className="flex-1 overflow-y-auto px-4 py-3">
              {threadLoading ? (
                <div className="text-center text-sm text-zinc-400">Loading…</div>
              ) : (
                <div className="flex flex-col gap-2">
                  {thread.map((m) => (
                    <div key={m.id} className={`flex ${m.direction === 'OUTBOUND' ? 'justify-end' : 'justify-start'}`}>
                      <div
                        className={`max-w-[75%] rounded-2xl px-3 py-2 text-sm ${
                          m.direction === 'OUTBOUND' ? 'bg-sky-600 text-white' : 'bg-zinc-100 text-zinc-900'
                        }`}
                      >
                        <p className="whitespace-pre-wrap break-words">{m.body}</p>
                        <p className={`mt-1 text-[10px] tabular-nums ${m.direction === 'OUTBOUND' ? 'text-sky-100' : 'text-zinc-400'}`}>
                          {formatBubbleTime(m.createdAt)}
                          {m.direction === 'OUTBOUND' && m.status !== 'SENT' ? ' · Not sent' : ''}
                        </p>
                      </div>
                    </div>
                  ))}
                  <div ref={bottomRef} />
                </div>
              )}
            </div>

            <form
              onSubmit={(e) => {
                e.preventDefault();
                void sendReply();
              }}
              className="flex items-center gap-2 border-t border-zinc-200 p-3"
            >
              <input
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                placeholder="Type a reply…"
                className="min-w-0 flex-1 rounded-full border border-zinc-300 px-4 py-2 text-sm"
              />
              <button
                type="submit"
                disabled={!draft.trim() || sending}
                className="shrink-0 rounded-full bg-sky-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
              >
                Send
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  );
}
