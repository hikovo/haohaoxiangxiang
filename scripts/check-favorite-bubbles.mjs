import fs from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
import assert from 'node:assert/strict';
import {reviewedBubbles, splitCaption} from '../src/generated/offline-rules.js';

const source = fs.readFileSync(new URL('../src/mobile.ts', import.meta.url), 'utf8');
class Element {
  children = []; events = {}; textContent = ''; className = ''; style = {}; classList = {add() {}};
  constructor(tag) { this.tag = tag; }
  append(...items) { this.children.push(...items); }
  replaceChildren(...items) { this.children = items; }
  addEventListener(name, action) { this.events[name] = action; }
}
const favorites = [
  {id:'letter', role:'pet', message:'Only for my light', savedAt:'2026-10-04', archive:{kind:'letter'}},
  {id:'video', role:'pet', message:'整段配字', savedAt:'2026-10-04', archive:{kind:'video', manualBubbles:['第一条', '第二条', '第三条']}},
  {id:'user', role:'user', message:'我的消息', savedAt:'2026-10-04'},
];
const list = new Element('div');
const context = vm.createContext({
  exports:{}, document:{createElement:tag=>new Element(tag)},
  chatFavorites:favorites, chatFavoritesList:list, chatFavoritesEmpty:{}, chatFavoritesSummary:{},
  INITIAL_CHAT_GREETING:'我是小苏(☆_☆)你是？', userAvatar:'right.jpg', chatPetAvatar:()=> 'left.jpg',
  renderArchive:item=>Object.assign(new Element('div'), {className:'archive-message', kind:item.kind}),
  reviewedBubbles, splitMessageParts:splitCaption, showToast() {}, localStorage:{setItem() {}}, CHAT_FAVORITES_KEY:'test',
});
const code = source.slice(source.indexOf('function chatContents('), source.indexOf('function appendMessage(')) +
  source.slice(source.indexOf('function renderChatFavorites()'), source.indexOf("let previousThinkingText")) +
  '\nexports.render=renderChatFavorites;';
vm.runInContext(ts.transpileModule(code, {compilerOptions:{target:ts.ScriptTarget.ES2022, module:ts.ModuleKind.CommonJS}}).outputText, context);
context.exports.render();
const cards = [...list.children].reverse();
for (const card of cards) {
  assert.equal(card.children[0].children.length, 1);
  assert.equal(card.children[0].children[0].tag, 'small'); // date only, no sender name
  for (const line of card.children[1].children) {
    assert.match(line.className, /^chat-line (pet|user)$/);
    assert.equal(line.children[0].className, 'chat-avatar');
    assert.equal(line.children[1].className, 'chat-bubble-stack');
  }
}
assert.equal(cards[0].children[1].children.length, 2); // PDF then its caption
assert.equal(cards[0].children[1].children[1].children[1].children[0].textContent, 'Only for my light');
assert.equal(cards[1].children[1].children.length, 4); // video then three reviewed bubbles
assert.deepEqual(cards[1].children[1].children.slice(1).map(line=>line.children[1].children[0].textContent), ['第一条','第二条','第三条']);
assert.equal(cards[2].children[1].children[0].children[0].src, 'right.jpg');
console.log('PASS: favorites reuse chat avatars, split bubbles and media; no sender-name label');
