'use client';

// Draws a small red unread badge onto the site's own favicon.ico and swaps the page's
// `<link rel="icon">` to the resulting canvas data URL (see MessagesNotifierIcon) — the one
// browser-native way to surface "you have new messages" in the tab strip without needing the
// Notification permission at all. Works even when that permission was never granted, was denied,
// or the browser doesn't support the Notification API in the first place (most mobile browsers).

let baseImage: HTMLImageElement | null = null;
let baseImageLoading: Promise<HTMLImageElement> | null = null;

function loadBaseImage(): Promise<HTMLImageElement> {
  if (baseImage) return Promise.resolve(baseImage);
  if (baseImageLoading) return baseImageLoading;
  baseImageLoading = new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      baseImage = img;
      resolve(img);
    };
    img.onerror = () => reject(new Error('favicon failed to load'));
    img.src = '/favicon.ico';
  });
  return baseImageLoading;
}

function faviconLink(): HTMLLinkElement {
  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (!link) {
    link = document.createElement('link');
    link.rel = 'icon';
    document.head.appendChild(link);
  }
  return link;
}

/** `count <= 0` restores the plain favicon; any positive count draws a red circle (the number for
 * 1-9, "9+" beyond) in the bottom-right corner. Best-effort: silently no-ops on failure (e.g. an
 * ancient browser with no canvas support, or the favicon fetch failing) — a missing badge is never
 * worth breaking the page over. */
export async function setFaviconBadge(count: number): Promise<void> {
  try {
    const link = faviconLink();
    if (count <= 0) {
      link.href = '/favicon.ico';
      return;
    }

    const img = await loadBaseImage();
    const size = 64;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.drawImage(img, 0, 0, size, size);

    const badgeRadius = size * 0.28;
    const cx = size - badgeRadius - 2;
    const cy = size - badgeRadius - 2;
    ctx.beginPath();
    ctx.arc(cx, cy, badgeRadius, 0, Math.PI * 2);
    ctx.fillStyle = '#dc2626';
    ctx.fill();
    ctx.lineWidth = size * 0.045;
    ctx.strokeStyle = '#ffffff';
    ctx.stroke();

    const label = count > 9 ? '9+' : String(count);
    ctx.fillStyle = '#ffffff';
    ctx.font = `700 ${badgeRadius}px system-ui, sans-serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(label, cx, cy + 1);

    link.href = canvas.toDataURL('image/png');
  } catch {
    // Best-effort — see doc comment above.
  }
}
