/* 把浏览器侧全局动作号计数器垫高到 Java 权威态的最大号（只增不回退）。实参 a: {max} 或 数字。
 *
 * 用途：整页导航（含 pushState 换 URL 后的整文档替换）会新建文档，window.__rolePickSeq /
 * __roleMaxNo 全部从零开始；若用户在新页的首次点击早于主循环回灌，新页会从 1 起号，
 * 与上一页已用号撞车 —— 面板上表现为"两个页面都出现 1"，而生成的 step 按号排序时两页元素
 * 会互相穿插错位。此处在【检测到页类变化的那一刻】立刻垫高计数器，闭合这个窗口。 */
(a) => {
  try {
    var m = 0;
    if (typeof a === 'number') { m = a; }
    else if (a && typeof a.max === 'number') { m = a.max; }
    else if (a && a.max != null) { m = Number(a.max) || 0; }
    if (!(m > 0)) return;
    var before = window.__rolePickSeq;
    if (typeof window.__rolePickSeq !== 'number' || window.__rolePickSeq < m) window.__rolePickSeq = m;
    if (typeof window.__roleMaxNo !== 'number' || window.__roleMaxNo < m) window.__roleMaxNo = m;
    try {
      console.log('[picker-diag][seq-baseline] before=' + before + ' setTo=' + m);
    } catch (e) {}
  } catch (e) {}
}
