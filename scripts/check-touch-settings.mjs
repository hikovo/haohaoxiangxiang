import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import ts from 'typescript';

function module(path, extras = {}) {
  const source = fs.readFileSync(new URL(path, import.meta.url), 'utf8').replace(/^import .*;\n/gm, '');
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
  const context = vm.createContext({ exports: {}, performance, window: {}, navigator: {}, ...extras });
  vm.runInContext(code, context);
  return context.exports;
}
const {createHapticTap, setupHaptics} = module('../src/haptics.ts');
let time = 0, pulses = 0;
const tap = createHapticTap(() => pulses++, () => time);
tap(); tap(); assert.equal(pulses, 1);
time = 79; tap(); assert.equal(pulses, 1);
time = 80; tap(); assert.equal(pulses, 2);
assert.doesNotThrow(createHapticTap(() => { throw new Error('Unsupported'); }));
assert.doesNotThrow(createHapticTap(() => Promise.reject(new Error('No native bridge'))));
class Element {
  constructor(eligible = true, disabled = false) { this.eligible = eligible; this.disabled = disabled; }
  closest() { return this.eligible ? this : null; }
  matches() { return this.disabled; }
}
class HTMLInputElement extends Element { constructor(type) { super(); this.type = type; } }
const api = module('../src/haptics.ts', {Element, HTMLInputElement});
const events = new Map();
const root = {contains: () => true, addEventListener: (name, callback) => events.set(name, callback), removeEventListener: name => events.delete(name)};
let feedback = 0;
const cleanup = api.setupHaptics(root, () => feedback++);
events.get('click')({target: new Element()});
events.get('click')({target: new Element(true, true)});
events.get('click')({target: new Element(false)});
events.get('change')({target: new HTMLInputElement('text')});
assert.equal(feedback, 1);
events.get('change')({target: new HTMLInputElement('range')});
assert.equal(feedback, 2);
assert.equal(events.has('pointermove'), false); assert.equal(events.has('input'), false);
cleanup(); assert.equal(events.size, 0);
const {daysInMonth, dateString} = module('../src/wheel-picker.ts', {lightHaptic: () => {}});
assert.equal(daysInMonth(2028, 2), 29);
assert.equal(daysInMonth(2027, 2), 28);
assert.equal(dateString(2027, 2, 31), '2027-02-28');
assert.equal(dateString(2028, 2, 29), '2028-02-29');
const source = fs.readFileSync(new URL('../src/mobile.ts', import.meta.url), 'utf8');
assert.match(source, /PET_DISPLAY_WIDTH = 125/);
assert.match(source, /restoreNumber\(SCALE_KEY, 0\.6, MIN_SCALE, MAX_SCALE\)/);
assert.match(source, /classList\.toggle\("chat-page-active", page === "chat"\)/);
const appearance = fs.readFileSync(new URL('../src/chat-appearance.ts', import.meta.url), 'utf8');
assert.match(appearance, /app\.style\.setProperty\('--chat-wallpaper'/);
assert.match(appearance, /app\.classList\.toggle\('has-chat-wallpaper'/);
assert.doesNotMatch(appearance, /page\.style\.backgroundImage/);
const styles = fs.readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8');
assert.match(styles, /has-chat-wallpaper \.page-chat-log\s*\{[^}]*background-image: var\(--chat-wallpaper\)/);
assert.match(styles, /#page-chat \.page-chat-log\s*\{[^}]*margin: 0 -18px 6px/);
const native = fs.readFileSync(new URL('../plugins/mobile-pet/android/src/main/java/OverlayPetService.kt', import.meta.url), 'utf8');
const menu = native.slice(native.indexOf('private fun buildMenu'), native.indexOf('private fun showTodayTasks'));
assert.equal((menu.match(/addPair\(/g) || []).length, 3); // helper plus two rows
assert.match(menu, /addPair\("互动", "隐藏兔兔"/);
assert.match(menu, /addPair\("提醒", "今日待办"/);
assert.doesNotMatch(menu, /悬浮聊天|结束专注|模式与大小|收起菜单/);
for (const name of ['BubbleReminderWorker', 'DailyReminderWorker', 'OverlayPetService']) {
  const code = fs.readFileSync(new URL(`../plugins/mobile-pet/android/src/main/java/${name}.kt`, import.meta.url), 'utf8');
  assert.match(code, /setSmallIcon\(R\.drawable\.ic_sanhao_notification\)/);
  assert.match(code, /setLargeIcon\(NotificationRabbit\.portrait/);
}
console.log('PASS: light haptics/throttling/failure isolation, valid leap-year dates, mini-sized rabbit, three-row overlay menu and notification icon call sites');
