export function setupChatTypography(open: (panel: HTMLElement) => void) {
  const key = 'sanhao.chat-font.v1';
  const page = document.querySelector<HTMLElement>('#mobile-app')!;
  const input = document.querySelector<HTMLInputElement>('#chat-font-size')!;
  const sample = document.querySelector<HTMLElement>('#chat-font-sample')!;
  function apply(value: number) {
    const size = Number.isFinite(value) ? Math.max(13, Math.min(20, value)) : 14.5;
    const ratio = size / 14.5;
    page.style.setProperty('--chat-font-size', `${size}px`);
    page.style.setProperty('--chat-gap', `${12 * ratio}px`);
    page.style.setProperty('--chat-avatar-size', `${40 * Math.min(1.15, ratio)}px`);
    page.style.setProperty('--chat-padding-y', `${9 * ratio}px`);
    page.style.setProperty('--chat-padding-x', `${12 * ratio}px`);
    input.value = String(size); sample.style.fontSize = `${size}px`;
    document.querySelector('#chat-font-summary')!.textContent = size === 14.5 ? '刚刚好的大小' : size < 14.5 ? '小巧一点' : '看得更清楚';
    localStorage.setItem(key, String(size));
  }
  apply(Number(localStorage.getItem(key) || 14.5));
  input.addEventListener('input', () => apply(Number(input.value)));
  document.querySelector('#chat-font-default')!.addEventListener('click', () => apply(14.5));
  document.querySelector('#open-chat-font')!.addEventListener('click', () => open(document.querySelector<HTMLElement>('#chat-font-panel')!));
}
