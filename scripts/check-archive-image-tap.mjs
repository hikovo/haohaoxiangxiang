import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import ts from 'typescript';
const source=fs.readFileSync(new URL('../src/offline-blindbox.ts',import.meta.url),'utf8').replace(/^import .*;\n/gm,'');
class Element {
  constructor(tag){this.tag=tag;this.children=[];this.events={};}
  append(...children){this.children.push(...children);}
  addEventListener(name,handler){this.events[name]=handler;}
}
const document={createElement:tag=>new Element(tag)};
const context=vm.createContext({exports:{},document});
vm.runInContext(ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,context);
const toasts=[];
const rendered=context.exports.renderArchive({kind:'image',derivedFile:'test.jpg',reply:'配字'},message=>toasts.push(message));
const image=rendered.children[0].children[0];let stopped=false;
image.events.click({stopPropagation(){stopped=true;}});
assert(stopped);assert.deepEqual(toasts,['可以去网盘看高清版喔～']);
assert.equal(rendered.children[0].children.length,1);
const guide=JSON.parse(fs.readFileSync(new URL('../content/app-guide.json',import.meta.url),'utf8'));
assert(!JSON.stringify(guide).includes('点照片可以放大'));
console.log(JSON.stringify({passed:true,imageTap:'toast-only',readerUnchanged:true}));
