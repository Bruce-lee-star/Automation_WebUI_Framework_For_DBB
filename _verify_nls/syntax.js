const fs = require('fs');
const src = fs.readFileSync('../src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/page/scan/RoleElementPicker.java', 'utf8');
const start = src.indexOf('private static final String START_SCRIPT = """');
const bodyStart = src.indexOf('"""', start) + 3;
const bodyEnd = src.indexOf('"""', bodyStart);
const script = src.slice(bodyStart, bodyEnd);
if (!script || script.length < 100) { console.error('FAIL: 未提取到 START_SCRIPT'); process.exit(1); }
try {
  // 仅做语法检查（不执行）：new Function 会在语法错误时抛 SyntaxError
  new Function(script);
  console.log('PASS: START_SCRIPT 语法正确 (' + script.length + ' 字符)');
} catch (e) {
  console.error('FAIL: START_SCRIPT 语法错误 ->', e.message);
  process.exit(1);
}
