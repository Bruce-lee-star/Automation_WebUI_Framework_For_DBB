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
  # 修正：根 pom 从未定义 junit.version（实为 junit.jupiter.version / junit.platform.version），
  # 原条目使 JUnit 一行被静默跳过（只有 WARN、不参与校验）。
  "JUnit|junit.jupiter.version"
  "Platform|junit.platform.version"
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

# ── CT2-23：文档路径引用 + 模块拓扑门禁 ───────────────────────────────────────
# 原门禁只比版本号，不校验模块名/文档路径引用（CT2-22 类问题无门禁可抓）。补两条：
#   ① pom.xml / README.md 中出现的 docs/**/*.md 必须真实存在（悬空引用即失败）；
#   ② 根 pom 每个 <module> 必须解析到含 pom.xml 的目录。
if ! $FIX_MODE; then
  mapfile -t doc_refs < <(grep -ohE 'docs/[^[:space:]]*\.md' "$POM" "$README" 2>/dev/null | sort -u || true)
  for ref in "${doc_refs[@]+"${doc_refs[@]}"}"; do
    [[ -z "$ref" ]] && continue
    if [[ -f "$ROOT/$ref" ]]; then
      echo "OK   : doc ref exists: $ref"
    else
      echo "FAIL : 悬空文档引用 '$ref'（被 pom.xml/README.md 引用但文件不存在）" >&2
      rc=1
    fi
  done

  # N-09 修复：README 实际用的是【范围引用】写法（`docs/<dir>/<NN>`~`<MM>`，无 .md 后缀）；
  #   原门禁只认带 .md 的精确路径，而仓库里没有这种写法 → 命中 0 条 → 循环体从不执行
  #   → 门禁【永远通过】（悬空引用/文档改名一律抓不到）。
  mapfile -t range_refs < <(grep -ohE 'docs/[A-Za-z0-9_/-]+/[0-9]{2}`?[ ]*~[ ]*`?[0-9]{2}' "$POM" "$README" 2>/dev/null | sort -u || true)
  range_count=0
  for raw in "${range_refs[@]+"${range_refs[@]}"}"; do
    [[ -z "$raw" ]] && continue
    norm="$(printf '%s' "$raw" | tr -d '`' | tr -d ' ')"
    case "$norm" in
      docs/*/[0-9][0-9]~[0-9][0-9]) ;;
      *) continue ;;
    esac
    rdir="${norm%/[0-9][0-9]~[0-9][0-9]}"   # docs/dir/01~13 -> docs/dir
    rfrom="${norm##*/}"                     # 01~13
    rfrom="${rfrom%%~*}"                    # 01
    rto="${norm##*~}"                       # 13
    # 先计入"已匹配到一条范围引用"：后续任何失败（目录不存在/起止颠倒/缺文档）都属"匹配到了但校验不过"，
    # 不得再触发下面的反空转守卫（否则一条坏引用会被报两次，且第二次的措辞是误导性的）。
    range_count=$((range_count + 1))
    if [[ ! -d "$ROOT/$rdir" ]]; then
      echo "FAIL : 范围引用指向的目录不存在：$rdir（来自 '$raw'）" >&2
      rc=1
      continue
    fi
    n=$((10#$rfrom))
    last=$((10#$rto))
    if [[ $n -gt $last ]]; then
      echo "FAIL : 范围引用起止颠倒：$raw" >&2
      rc=1
      continue
    fi
    missing=""
    while [[ $n -le $last ]]; do
      nn="$(printf '%02d' "$n")"
      if ! compgen -G "$ROOT/$rdir/$nn*.md" > /dev/null; then
        missing="$missing $nn"
      fi
      n=$((n + 1))
    done
    if [[ -n "$missing" ]]; then
      echo "FAIL : 范围引用 $rdir/$rfrom~$rto 缺文档：$missing" >&2
      rc=1
    else
      echo "OK   : doc range resolves: $rdir/$rfrom~$rto"
    fi
  done

  # N-09 反空转守卫：两类引用【一条都没匹配到】时必须失败 —— 永远为真的门禁比没有门禁更危险。
  if [[ "${#doc_refs[@]}" -eq 0 && "$range_count" -eq 0 ]]; then
    echo "FAIL : 文档引用门禁未匹配到任何引用（既无 docs/**.md 精确路径，也无范围引用）—— 门禁已与文档写法脱节，或文档不再引用 docs/；请修正本脚本正则或 README" >&2
    rc=1
  fi

  mapfile -t mods < <(grep -oP '(?<=<module>)[^<]+' "$POM" 2>/dev/null || true)
  for mod in "${mods[@]+"${mods[@]}"}"; do
    [[ -z "$mod" ]] && continue
    if [[ -f "$ROOT/$mod/pom.xml" ]]; then
      echo "OK   : module resolves: $mod"
    else
      echo "FAIL : 根 pom 声明 <module>$mod</module> 但 $mod/pom.xml 不存在" >&2
      rc=1
    fi
  done
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
