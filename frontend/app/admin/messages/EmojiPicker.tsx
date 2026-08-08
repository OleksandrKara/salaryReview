'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';

const PANEL_COLUMNS = 8;
const CELL_SIZE = 34;
const PANEL_PADDING = 8;
const ESTIMATED_ROW_COUNT_FOR_FLIP = 3; // only affects the upward-flip decision, doesn't need to be exact

/**
 * A small popover grid of emoji — shared by the composer's "insert emoji" button and each message
 * bubble's "react" button (see MessagesView.tsx), just with a different `emojis` list and
 * `onSelect` handler for each. Same hand-rolled fixed-position/outside-click-to-close pattern as
 * {@link ConversationMenu} (the first popover in this app, no floating-ui/radix dependency).
 */
export default function EmojiPicker({
  emojis,
  onSelect,
  trigger,
  ariaLabel,
}: {
  emojis: string[];
  onSelect: (emoji: string) => void;
  trigger: ReactNode;
  ariaLabel: string;
}) {
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState<{ left: number; top?: number; bottom?: number } | null>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  const panelWidth = Math.min(emojis.length, PANEL_COLUMNS) * CELL_SIZE + PANEL_PADDING * 2;
  const estimatedHeight = Math.ceil(emojis.length / PANEL_COLUMNS) * CELL_SIZE + PANEL_PADDING * 2;

  function openPicker() {
    const rect = buttonRef.current?.getBoundingClientRect();
    if (!rect) return;
    const estimated = Math.max(estimatedHeight, CELL_SIZE * ESTIMATED_ROW_COUNT_FOR_FLIP);
    const openUpward = window.innerHeight - rect.bottom < estimated && rect.top > estimated;
    const left = Math.max(8, Math.min(rect.left, window.innerWidth - panelWidth - 8));
    setPosition(
      openUpward
        ? { left, bottom: window.innerHeight - rect.top + 4 }
        : { left, top: rect.bottom + 4 },
    );
    setOpen(true);
  }

  useEffect(() => {
    if (!open) return;
    function handlePointerDown(e: MouseEvent) {
      const target = e.target as Node;
      if (panelRef.current?.contains(target) || buttonRef.current?.contains(target)) return;
      setOpen(false);
    }
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    function handleScroll() {
      setOpen(false);
    }
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    window.addEventListener('scroll', handleScroll, true);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('scroll', handleScroll, true);
    };
  }, [open]);

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        data-testid="emoji-picker-trigger"
        onClick={(e) => {
          e.stopPropagation();
          if (open) {
            setOpen(false);
          } else {
            openPicker();
          }
        }}
        aria-label={ariaLabel}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        {trigger}
      </button>
      {open && position && (
        <div
          ref={panelRef}
          role="menu"
          data-testid="emoji-picker-panel"
          onClick={(e) => e.stopPropagation()}
          style={{
            position: 'fixed',
            left: position.left,
            top: position.top,
            bottom: position.bottom,
            width: panelWidth,
            maxHeight: CELL_SIZE * 5 + PANEL_PADDING * 2,
          }}
          className="z-50 grid grid-cols-8 gap-0 overflow-y-auto rounded-lg border border-zinc-200 bg-white p-2 shadow-lg"
        >
          {emojis.map((emoji) => (
            <button
              key={emoji}
              type="button"
              role="menuitem"
              data-testid="emoji-picker-option"
              onClick={() => {
                onSelect(emoji);
                setOpen(false);
              }}
              className="flex h-8 w-8 items-center justify-center rounded text-lg hover:bg-zinc-100"
            >
              {emoji}
            </button>
          ))}
        </div>
      )}
    </>
  );
}
