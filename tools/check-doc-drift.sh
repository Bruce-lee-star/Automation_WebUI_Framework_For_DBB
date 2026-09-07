#!/usr/bin/env bash
# =============================================================================
# T4-4 文档防漂移校验
# -----------------------------------------------------------------------------
# 校验 README.md 技术栈表中的版本号与根 pom.xml <properties> 完全一致。
# 版本号的「单一事实来源」是根 pom 的 <properties>（含新增的 <cucumber.version>）。
#
# 用法：
#   bash tools/check-doc-drift.sh          # 检查模式（CI 使用）：不一致则退出码 1
#   bash tools/check-doc-drift.sh --fix    # 依据 pom 属性重写 README 中的版本号
#
# 设计要点（企业级）：
#   - 不依赖 Maven，纯文本比对，CI 秒级完成、无网络依赖。
#   - --fix 模式用「标签后最近的三元组版本」做定点替换，避免误伤同行其他版本。
#   - 框架本身不依赖 Spring：README 不应再把 Spring Context 列为框架技术栈
#     （Spring 仅 route-demo-* 演示服务使用，见 README 对应小节）。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM="$ROOT/pom.xml"
README="$ROOT/README.md"

if [[ ! -f "$POM" || ! -f "$README" ]]; then
  echo "ERROR: 找不到 pom.xml 或 README.md（ROOT=$ROOT）" >&2
  exit 2
fi

FIX_MODE=false
[[ "${1:-}" == "--fix" ]] && FIX_MODE=true

# 组件标签 -> pom 属性名（标签用于在 README 中定位版本号）
# 顺序：label|property
MAP=(
  "Playwright|playwright.version"
  "Serenity|serenity.version"
  "serenity-maven-plugin|serenity.version"
  "Cucumber|cucumber.version"
  "JUnit|junit.version"
  "Logback|logback.version"
  "typesafe.config|typesafe.config.version"
  "Gson|gson.version"
  "JsonPath|json.path.version"
)

get_prop() {
  grep -oP "<${1}>\K[^<]+" "$POM" | head -n1
}

rc=0
for entry in "${MAP[@]}"; do
  label="${entry%%|*}"
  prop="${entry##*|}"
  expected="$(get_prop "$prop")"
  if [[ -z "$expected" ]]; then
    echo "WARN: pom 中未找到属性 <$prop>，跳过 $label" >&2
    continue
  fi
  if $FIX_MODE; then
    # 将 README 中 “<label>...<version>” 的版本号替换为 pom 值（全局、仅改 label 后的首个三元组）
    perl -i -pe "s/(${label}(?:(?!\\d+\\.\\d+\\.\\d+).)*?)\\d+\\.\\d+\\.\\d+/\${1}${expected}/g" "$README"
    echo "FIX : $label -> $expected (pom <$prop>)"
  else
    if grep -q "$expected" "$README"; then
      echo "OK   : $label = $expected (pom <$prop>)"
    else
      echo "FAIL : $label 在 README 中未找到版本 $expected（pom <$prop>）；请运行 'bash tools/check-doc-drift.sh --fix' 或手动同步" >&2
      rc=1
    fi
  fi
done

# Spring 不是框架依赖：README 不应将其列为框架技术栈
if ! $FIX_MODE; then
  if grep -qE "Spring Context 6\.1\.6" "$README"; then
    echo "FAIL : README 仍把 Spring Context 6.1.6 列为框架依赖（框架本身不依赖 Spring；仅 demo 使用）" >&2
    rc=1
  fi
else
  if grep -qE "Spring Context 6\.1\.6" "$README"; then
    echo "WARN : README 仍含 'Spring Context' 框架依赖行，请手动移除（框架不依赖 Spring）" >&2
  fi
fi

if $FIX_MODE; then
  echo "README 已依据 pom 重写（若仍含 Spring Context 框架依赖行，请手动移除）。"
else
  if [[ $rc -eq 0 ]]; then
    echo "文档一致性校验通过 ✅"
  else
    echo "文档一致性校验失败 ❌（README 与 pom 存在版本漂移）" >&2
  fi
fi
exit $rc
