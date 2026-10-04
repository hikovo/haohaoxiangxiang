import { lightHaptic } from './haptics';

export const daysInMonth = (year: number, month: number) => new Date(year, month, 0).getDate();
const two = (value: number) => String(value).padStart(2, '0');
export const dateString = (year: number, month: number, day: number) => `${year}-${two(month)}-${two(Math.min(day, daysInMonth(year, month)))}`;

/** Shared touch-first picker. Values are committed only by the Done button. */
export function setupWheelPickers(root: HTMLElement) {
  const dialog = document.createElement('dialog');
  dialog.className = 'wheel-picker';
  dialog.innerHTML = `<header><button type="button" data-wheel="cancel">取消</button><h2>选个时间</h2><button type="button" data-wheel="done">完成</button></header><p class="wheel-summary"></p><div class="wheel-columns"></div><button type="button" class="wheel-clear">暂不设置时间</button><p class="wheel-hint">轻轻滑动，停在喜欢的那一天</p>`;
  root.append(dialog);
  const columns = dialog.querySelector<HTMLElement>('.wheel-columns')!;
  const summary = dialog.querySelector<HTMLElement>('.wheel-summary')!;
  let input: HTMLInputElement | HTMLSelectElement | null = null;
  let values: number[] = [];
  let date = true;
  let time = false;
  let durationUnit = '分钟';
  let choiceLabels: string[] | undefined;
  let dayColumn: HTMLElement | undefined;
  const columnValues = new Map<HTMLElement, number[]>();
  const selected = (column: HTMLElement) => {
    const list = columnValues.get(column)!;
    return list[Math.max(0, Math.min(list.length - 1, Math.round(column.scrollTop / 44)))];
  };
  function updateSummary() {
    summary.textContent = date ? `${values[0]} 年 ${values[1]} 月 ${values[2]} 日` : time ? `${two(values[0])} : ${two(values[1])}` : choiceLabels ? choiceLabels[values[0]] : `${values[0]} ${durationUnit}`;
  }
  function fillColumn(column: HTMLElement, list: number[], index: number, suffix: string) {
    columnValues.set(column, list);
    column.replaceChildren(...list.map(value => {
      const option = document.createElement('button');
      option.type = 'button'; option.role = 'option'; option.textContent = choiceLabels ? choiceLabels[value] : `${date && index === 0 || !date && !time ? value : two(value)}${suffix}`;
      option.setAttribute('aria-label', option.textContent);
      option.addEventListener('click', () => { column.scrollTop = list.indexOf(value) * 44; sync(); });
      return option;
    }));
    column.scrollTop = Math.max(0, list.indexOf(values[index])) * 44;
    function sync() {
      values[index] = selected(column);
      column.querySelectorAll('button').forEach((button, i) => button.setAttribute('aria-selected', String(list[i] === values[index])));
      if (date && index < 2 && dayColumn) {
        const count = daysInMonth(values[0], values[1]);
        if (columnValues.get(dayColumn)?.length !== count) {
          values[2] = Math.min(values[2], count);
          fillColumn(dayColumn, Array.from({ length: count }, (_, i) => i + 1), 2, '日');
        }
      }
      updateSummary();
    }
    column.onscroll = sync;
    column.onkeydown = event => {
      if (!['ArrowUp', 'ArrowDown'].includes(event.key)) return;
      event.preventDefault(); column.scrollTop += event.key === 'ArrowDown' ? 44 : -44; sync(); lightHaptic();
    };
    column.querySelectorAll('button').forEach((button, i) => button.setAttribute('aria-selected', String(list[i] === values[index])));
  }
  function open(target: HTMLInputElement | HTMLSelectElement) {
    input = target; date = target.type === 'date';
    time = target.type === 'time';
    durationUnit = target.id === 'timer-seconds' ? '秒' : '分钟';
    choiceLabels = target instanceof HTMLSelectElement ? [...target.options].map(option => option.textContent || '') : undefined;
    const today = new Date();
    values = date ? (target.value || dateString(today.getFullYear(), today.getMonth() + 1, today.getDate())).split('-').map(Number)
      : time ? (target.value || `${two(today.getHours())}:${two(today.getMinutes())}`).split(':').map(Number)
      : [target instanceof HTMLSelectElement ? target.selectedIndex : Number(target.value || 25)];
    const label = target.closest('label');
    dialog.querySelector('h2')!.textContent = label ? [...label.childNodes].filter(node => node.nodeType === Node.TEXT_NODE).map(node => node.textContent).join('').trim() || (date ? '选个日子' : '选个时间') : date ? '选个日子' : '选个时间';
    dialog.querySelector<HTMLElement>('.wheel-hint')!.textContent = date ? '轻轻滑动，选个日子吧' : '轻轻滑动，兔兔记住这个时间';
    dialog.querySelector<HTMLElement>('.wheel-clear')!.hidden = target.id !== 'memo-time-input' || target.required;
    columns.replaceChildren(); columnValues.clear(); dayColumn = undefined;
    const min = target instanceof HTMLInputElement ? target.min : '';
    const max = target instanceof HTMLInputElement ? target.max : '';
    const lists = date ? [Array.from({ length: Number(max.slice(0, 4) || 2100) - Number(min.slice(0, 4) || 1900) + 1 }, (_, i) => i + Number(min.slice(0, 4) || 1900)), Array.from({ length: 12 }, (_, i) => i + 1), Array.from({ length: daysInMonth(values[0], values[1]) }, (_, i) => i + 1)] : time ? [Array.from({ length: 24 }, (_, i) => i), Array.from({ length: 60 }, (_, i) => i)] : [choiceLabels ? choiceLabels.map((_, i) => i) : Array.from({ length: Number(max || 180) - Number(min || 1) + 1 }, (_, i) => i + Number(min || 1))];
    columns.classList.toggle('single-wheel', lists.length === 1);
    lists.forEach((list, index) => {
      const wrapper = document.createElement('div'); wrapper.className = 'wheel-track';
      const column = document.createElement('div'); column.className = 'wheel-column'; column.role = 'listbox'; column.tabIndex = 0; column.setAttribute('aria-label', date ? ['年', '月', '日'][index] : time ? ['时', '分'][index] : choiceLabels ? '提醒方式' : durationUnit);
      wrapper.append(column); columns.append(wrapper);
      if (date && index === 2) dayColumn = column;
      fillColumn(column, list, index, date ? ['年', '月', '日'][index] : time ? ['时', '分'][index] : choiceLabels ? '' : durationUnit);
    });
    if (!dialog.open) dialog.showModal();
    requestAnimationFrame(() => [...columns.querySelectorAll<HTMLElement>('.wheel-column')].forEach((column, index) => {
      column.scrollTop = Math.max(0, columnValues.get(column)!.indexOf(values[index])) * 44;
    }));
    lightHaptic(); updateSummary();
  }
  const commit = (value: string) => {
    if (!input) return;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    dialog.close();
  };
  dialog.querySelector('[data-wheel=cancel]')!.addEventListener('click', () => dialog.close());
  dialog.querySelector('[data-wheel=done]')!.addEventListener('click', () => {
    values = [...columns.querySelectorAll<HTMLElement>('.wheel-column')].map(selected);
    let value = date ? dateString(values[0], values[1], values[2]) : time ? `${two(values[0])}:${two(values[1])}` : input instanceof HTMLSelectElement ? input.options[values[0]].value : String(values[0]);
    if (input instanceof HTMLInputElement && (date || time)) {
      if (input.min && value < input.min) value = input.min;
      if (input.max && value > input.max) value = input.max;
    }
    commit(value);
  });
  dialog.querySelector('.wheel-clear')!.addEventListener('click', () => commit(''));
  root.querySelectorAll<HTMLInputElement | HTMLSelectElement>('input[type=date],input[type=time],#timer-minutes,#timer-seconds,select').forEach(target => {
    if (target instanceof HTMLInputElement) target.readOnly = true;
    target.classList.add('wheel-trigger');
    if (target instanceof HTMLInputElement && ['date', 'time'].includes(target.type)) {
      // Android date/time inputs render a native arrow outside CSS control.
      // Keep the form value, but show our own identical picker button on every OS.
      const trigger = document.createElement('button');
      trigger.type = 'button';
      trigger.className = `${target.className} wheel-value-button`;
      trigger.dataset.pickerFor = target.id;
      const value = document.createElement('span');
      const arrow = document.createElement('i'); arrow.className = 'wheel-right-arrow'; arrow.setAttribute('aria-hidden', 'true');
      trigger.append(value, arrow);
      target.after(trigger);
      target.classList.add('wheel-native-input');
      target.tabIndex = -1;
      const refresh = () => {
        value.textContent = target.value || (target.type === 'date' ? '年 / 月 / 日' : '选个时间');
        trigger.disabled = target.disabled;
      };
      target.addEventListener('input', refresh); target.addEventListener('change', refresh);
      const panel = target.closest('.mobile-sheet, .memo-moment-detail');
      if (panel) new MutationObserver(refresh).observe(panel, {attributes:true, attributeFilter:['hidden']});
      trigger.addEventListener('click', () => { refresh(); open(target); });
      refresh();
    }
    target.addEventListener('pointerdown', event => { if (target instanceof HTMLSelectElement) { event.preventDefault(); open(target); } });
    target.addEventListener('click', event => { event.preventDefault(); if (!dialog.open) open(target); });
    target.addEventListener('keydown', event => {
      if (!(event instanceof KeyboardEvent)) return;
      if (['Enter', ' '].includes(event.key)) { event.preventDefault(); open(target); }
    });
  });
}
