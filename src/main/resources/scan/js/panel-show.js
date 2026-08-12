            (function() {
              var code = window.__pickerCode || '';
              var old = document.getElementById('__roleCodeOverlay');
              if (old) old.remove();
              window.__codePanelClosed = false;

              var overlay = document.createElement('div');
              overlay.id = '__roleCodeOverlay';
              overlay.style.cssText = 'position:fixed;inset:0;z-index:2147483647;' +
                'background:rgba(0,0,0,.55);display:flex;align-items:center;justify-content:center;';

              var panel = document.createElement('div');
              panel.style.cssText = 'position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);' +
                'width:min(860px,92vw);max-height:86vh;display:flex;flex-direction:column;' +
                'background:#1e1e1e;color:#e0e0e0;border-radius:8px;box-shadow:0 8px 30px rgba(0,0,0,.5);' +
                'font:13px/1.5 Consolas,Monaco,monospace;overflow:hidden;';

              var header = document.createElement('div');
              header.style.cssText = 'padding:10px 14px;background:#1e88e5;color:#fff;font-weight:bold;' +
                'display:flex;align-items:center;justify-content:space-between;cursor:move;';
              var title = document.createElement('span');
              title.textContent = '生成的 @RoleElement Page 代码';
              header.appendChild(title);
              var status = document.createElement('span');
              status.style.cssText = 'font-weight:normal;font-size:12px;opacity:.9;';
              header.appendChild(status);

              (function() {
                var dragging = false, sx = 0, sy = 0, ox = 0, oy = 0;
                header.addEventListener('mousedown', function(e) {
                  if (e.button !== 0) return;
                  dragging = true;
                  sx = e.clientX; sy = e.clientY;
                  ox = panel.offsetLeft; oy = panel.offsetTop;
                  e.preventDefault();
                });
                document.addEventListener('mousemove', function(e) {
                  if (!dragging) return;
                  var nl = ox + e.clientX - sx;
                  var nt = oy + e.clientY - sy;
                  // 视口边界约束：保证标题栏始终留在屏幕内，不会被顶边/侧边裁掉而抓不住
                  var w = panel.offsetWidth, h = panel.offsetHeight;
                  if (nl < 0) nl = 0;
                  if (nl + w > window.innerWidth) nl = window.innerWidth - w;
                  if (nt < 0) nt = 0;
                  if (h <= window.innerHeight && nt + h > window.innerHeight) nt = window.innerHeight - h;
                  panel.style.left = nl + 'px';
                  panel.style.top = nt + 'px';
                  panel.style.right = 'auto';
                  panel.style.bottom = 'auto';
                  panel.style.transform = 'none';
                });
                document.addEventListener('mouseup', function() { dragging = false; });
              })();

              var ta = document.createElement('textarea');
              ta.readOnly = true;
              ta.value = code;
              ta.style.cssText = 'flex:1;min-height:340px;margin:0;padding:12px 14px;border:0;resize:none;' +
                'background:#1e1e1e;color:#d4d4d4;font:13px/1.55 Consolas,Monaco,monospace;outline:none;white-space:pre;';

              var footer = document.createElement('div');
              footer.style.cssText = 'padding:10px 14px;background:#252526;display:flex;gap:10px;justify-content:flex-end;';

              function mkBtn(text, bg) {
                var b = document.createElement('button');
                b.textContent = text;
                b.style.cssText = 'padding:7px 18px;border:0;border-radius:5px;cursor:pointer;' +
                  'font:13px/1 sans-serif;color:#fff;background:' + bg + ';';
                return b;
              }
              var copyBtn = mkBtn('复制代码', '#43a047');
              var closeBtn = mkBtn('关闭', '#616161');

              copyBtn.onclick = function() {
                function ok() {
                  status.textContent = '已复制到剪贴板 ✔';
                  status.style.color = '#00e676';
                  status.style.fontWeight = 'bold';
                  status.style.fontSize = '14px';
                  status.style.background = 'rgba(0,230,118,.18)';
                  status.style.borderRadius = '3px';
                  status.style.padding = '1px 6px';
                  status.style.textShadow = '0 0 6px rgba(0,230,118,.6)';
                  copyBtn.textContent = '已复制';
                }
                try {
                  if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(code).then(ok, function() { fallback(); });
                  } else { fallback(); }
                } catch (e) { fallback(); }
                function fallback() {
                  ta.focus(); ta.select();
                  try { document.execCommand('copy'); ok(); }
                  catch (e2) { status.textContent = '复制失败，请手动选中复制'; }
                }
              };
              closeBtn.onclick = function() {
                window.__codePanelClosed = true;
                overlay.remove();
              };

              footer.appendChild(copyBtn);
              footer.appendChild(closeBtn);
              panel.appendChild(header);
              panel.appendChild(ta);
              panel.appendChild(footer);
              overlay.appendChild(panel);
              (document.body || document.documentElement).appendChild(overlay);
            })();