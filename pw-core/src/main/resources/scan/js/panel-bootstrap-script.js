(function(){ /* 【修复"打开新页面/整页导航后没有重新创建面板"】
         原来：__rolePanelEnabled==='0' 就无条件 return。'0' 是"用户关闭过面板"的【粘性墓碑】
         （close-panel-js.js 写入，并用 __rolePanelForce=false 显式清掉兜底），于是该 origin 上
         后续【每一个新文档】都在这里早退 ⇒ 下面的 __rolePanelForce=true 永不置位 ⇒
         panel-core-a.js 的 UI 门禁（'1' || __rolePanelForce）双假 ⇒ 面板永不重建（表现为
         "打开新页面，没有重新创建面板"，且此后任何导航都恢复不了）。
         现改为：墓碑不压过【进行中的拾取会话】—— __rolePickSessionOn==='1'（▶ 开始拾取期间由
         门控脚本/start 写入）即视为必须重建面板，同时把 '1' 写回 localStorage 修复该墓碑。
         浏览态（无会话）仍旧尊重墓碑，不会无故弹出面板。 */
      var __panelFlag = null, __sessionOn = null;
      try { __panelFlag = localStorage.getItem('__rolePanelEnabled'); } catch(e) {}
      try { __sessionOn = localStorage.getItem('__rolePickSessionOn'); } catch(e) {}
      if (__panelFlag === '0' && __sessionOn !== '1') return; try{localStorage.setItem('__rolePanelEnabled','1');}catch(e){} try{window.__rolePanelForce=true;}catch(e){} try{var n=localStorage.getItem('__rolePageName'); if(n) window.__rolePageName=n;}catch(e){} })();(function(){ if (window.__mergeKey) return; window.__mergeKey = function(p){ try{   if (!p) return '';   if (p._sigKey) return p._sigKey;   if (typeof window.__sigKey === 'function') return window.__sigKey(p);   var pageKey = p._pageClass || '';   if (!pageKey) { try { pageKey = (location.origin||'') + (location.pathname||''); } catch(e){} }   return JSON.stringify([p._sig || '', pageKey]); }catch(e){ return ''; } }; })();