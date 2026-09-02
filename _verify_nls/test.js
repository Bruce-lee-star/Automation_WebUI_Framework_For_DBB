// 聚焦验证本次改动：normName + 角色分支的 nls key 反查逻辑。
// （START_SCRIPT 其余部分已在上一轮用 jsdom 跑通，这里只验证新增的查表分支。）
const { JSDOM } = require('jsdom');

const dom = new JSDOM(`<!DOCTYPE html><body>
  <button id="b1">Forgot username</button>
  <button id="b2">Some Unknown Label</button>
  <a id="lnk" href="#">Next</a>
</body>`, { runScripts: 'outside-only' });
const { window } = dom;

// 模拟 Java buildNlsReverseJson 的输出（已用 normalize 规范化值）
window.__nlsReverse = { 'Forgot username': 'forgot_username', 'Next': 'next' };

// 复刻注入脚本关键片段
function normName(s) {
  return (s || '').replace(/\r\n/g, '\n').replace(/\u00A0/g, ' ').replace(/\s+/g, ' ').trim();
}
const INTERACTIVE_ROLES = { button:1, link:1, checkbox:1, radio:1, tab:1,
  menuitem:1, menuitemcheckbox:1, menuitemradio:1, option:1, 'switch':1,
  textbox:1, combobox:1, listbox:1, searchbox:1, slider:1, spinbutton:1, treeitem:1 };
function getName(el){ return (el.textContent||'').replace(/\s+/g,' ').trim(); }
function getRole(el){ return (el.tagName||'').toLowerCase(); }
function computePick(t) {
  var cur = t, guard = 0;
  while (cur && guard++ < 5) {
    var r = (getRole(cur)||'').toLowerCase();
    if (INTERACTIVE_ROLES[r]) {
      var nm = getName(cur);
      if (nm) {
        var key = null;
        if (window.__nlsReverse) { key = window.__nlsReverse[normName(nm)] || null; }
        return { strategy:'role', role:r, name:nm, key:key, matched: !!key };
      }
      break;
    }
    cur = cur.parentElement;
  }
  return { strategy:'none' };
}

function ok(c,m){ console.log((c?'PASS':'FAIL')+': '+m); if(!c) process.exitCode=1; }

const p1 = computePick(window.document.getElementById('b1'));
const p2 = computePick(window.document.getElementById('b2'));
const p3 = computePick(window.document.getElementById('lnk'));

ok(p1.strategy==='role' && p1.role==='button', 'b1 role=button');
ok(p1.key==='forgot_username', 'b1 命中 key=forgot_username (实:'+p1.key+')');
ok(p1.matched===true, 'b1 matched');
ok(p2.key===null && p2.matched===false, 'b2 未命中 key=null');
ok(p3.key==='next' && p3.matched===true, 'link 命中 key=next (实:'+p3.key+')');

console.log('DONE');
