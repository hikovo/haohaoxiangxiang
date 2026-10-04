import { invoke } from '@tauri-apps/api/core';

export type ReminderHelpKind = 'daily' | 'todo' | 'countdown' | 'focus';
const shownThisSession = new Set<ReminderHelpKind>();
const seenKey = (kind: ReminderHelpKind) => `hhxx.reminder-system-help-seen.v1/${kind}`;

export function showReminderSettingsHelp(kind: ReminderHelpKind) {
  if (shownThisSession.has(kind)) return;
  try { if (localStorage.getItem(seenKey(kind)) === '1') return; } catch { /* Session fallback when storage is unavailable. */ }
  if (document.querySelector('#reminder-system-help')) return;
  const dialog = document.createElement('dialog');
  dialog.id = 'reminder-system-help';
  dialog.className = 'reminder-system-help';
  dialog.setAttribute('aria-labelledby', 'reminder-system-help-title');
  dialog.innerHTML = `
    <img src="/rabbit-head.png" alt="" aria-hidden="true" />
    <h2 id="reminder-system-help-title">让小提醒响起来</h2>
    <p>想收到铃声和震动，记得在手机设置里：</p>
    <ul><li>开启手机的声音与震动，调好通知音量。</li><li>允许「好好想想」发送通知，并在提醒通知中开启铃声和震动。</li></ul>
    <small>静音、勿扰或关闭通知时，提醒可能不会响。不同手机的设置名称会有一点不同。</small>
    <div class="reminder-system-help-actions"><button type="button" data-settings="sound">手机声音与震动 ›</button><button type="button" data-settings="notifications">好好想想通知设置 ›</button></div>
    <p class="reminder-system-help-status" role="status" hidden></p>
    <button type="button" data-dismiss>我知道啦</button>`;
  document.querySelector('#mobile-app')!.append(dialog);
  const dismiss = () => { dialog.close(); dialog.remove(); };
  dialog.querySelector('[data-dismiss]')!.addEventListener('click', dismiss);
  dialog.addEventListener('cancel', event => { event.preventDefault(); dismiss(); });
  dialog.querySelectorAll<HTMLButtonElement>('[data-settings]').forEach(button => button.addEventListener('click', async () => {
    const status = dialog.querySelector<HTMLElement>('[role="status"]')!;
    if (!('__TAURI_INTERNALS__' in window)) {
      status.textContent = '请在手机 App 中打开设置；网页预览不能跳转手机设置。';
      status.hidden = false;
      return;
    }
    button.disabled = true;
    try {
      await invoke('plugin:mobile-pet|reminder_style', { payload: JSON.stringify({ action: 'settings', target: button.dataset.settings }) });
      status.hidden = true;
    } catch {
      status.textContent = '没能直接打开，请到手机「设置」中找到声音与震动，以及「好好想想」的通知设置。';
      status.hidden = false;
    } finally { button.disabled = false; }
  }));
  dialog.showModal();
  shownThisSession.add(kind);
  try { localStorage.setItem(seenKey(kind), '1'); } catch { /* Do not repeat within this session. */ }
}
