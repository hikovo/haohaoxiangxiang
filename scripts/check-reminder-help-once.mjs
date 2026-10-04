import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';

const read = name => fs.readFileSync(new URL('../' + name, import.meta.url), 'utf8');
const source = read('src/reminder-settings-help.ts').replace(/^import .*$/m, '');
const javascript = ts.transpileModule(source, {compilerOptions: {target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS}}).outputText;
function runtime(storage = new Map(), failStorage = false) {
  let active = null, shown = 0, failDisplay = false;
  const dismiss = {addEventListener: (_, handler) => { dismiss.click = handler; }};
  const context = vm.createContext({exports: {}, window: {},
    localStorage: {
      getItem: key => { if (failStorage) throw Error('unavailable'); return storage.get(key) || null; },
      setItem: (key, value) => { if (failStorage) throw Error('unavailable'); storage.set(key, value); },
    },
    document: {
      querySelector: selector => selector === '#reminder-system-help' ? active : {append: dialog => { active = dialog; }},
      createElement: () => ({
        setAttribute() {}, querySelector: () => dismiss, querySelectorAll: () => [], addEventListener() {}, close() {},
        remove: () => { active = null; },
        showModal: () => { if (failDisplay) throw Error('display failed'); shown++; },
      }),
    },
  });
  vm.runInContext(javascript, context);
  return {show: context.exports.showReminderSettingsHelp, close: () => dismiss.click(),
    count: () => shown, displayFails: () => { failDisplay = true; }};
}
const storage = new Map(), first = runtime(storage);
for (const category of ['daily', 'todo', 'countdown', 'focus']) {
  const before = first.count();
  first.show(category); assert.equal(first.count(), before + 1); first.close();
  first.show(category); assert.equal(first.count(), before + 1);
}
assert.equal(storage.size, 4);
const reopened = runtime(storage);
for (const category of ['daily', 'todo', 'countdown', 'focus']) reopened.show(category);
assert.equal(reopened.count(), 0, 'Restart must retain all category flags');
const stacked = runtime();
stacked.show('daily'); stacked.show('todo'); assert.equal(stacked.count(), 1);
stacked.close(); stacked.show('todo'); assert.equal(stacked.count(), 2, 'Blocked dialog must not consume another category');
const unavailable = runtime(new Map(), true);
unavailable.show('focus'); unavailable.close(); unavailable.show('focus'); assert.equal(unavailable.count(), 1);
const failedStorage = new Map(), failed = runtime(failedStorage);
failed.displayFails(); assert.throws(() => failed.show('daily')); assert.equal(failedStorage.size, 0);
const app = read('src/mobile.ts');
for (const category of ['daily', 'todo', 'countdown', 'focus']) assert(app.includes(`showReminderSettingsHelp('${category}')`));
assert(read('src/reminder-styles.ts').includes('showReminderSettingsHelp(kind)'));
assert(!app.includes('showReminderSettingsHelp()'));
console.log('PASS: four category flags, once across items and restarts, no dialog stacking, session storage fallback, failed display not marked');
