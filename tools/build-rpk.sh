#!/usr/bin/env bash
# 弧光歌词 · RPK 构建脚本
#
# 为什么需要这个脚本：
#   官方 aiot-toolkit 2.x 依赖 @rspack/binding-android-arm64，该包未发布，
#   在 aarch64 Termux / Android 上无法加载。1.2.1-alpha.0 是最后一条纯 JS
#   （webpack 5）工具链，功能等价，可在 Termux 内直接出 RPK。
#   PC/Mac 上用 AIoT-IDE 或 `npm run build` / `npm run release` 即可，无需本脚本。
#
# 用法：
#   bash tools/build-rpk.sh debug     # 调试签名，输出 dist/Arclyric-1.0.0-debug.rpk
#   bash tools/build-rpk.sh release   # 正式签名，输出 dist/Arclyric-1.0.0-release.rpk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN="$ROOT/.tmp/toolchain"
KIT="$TOOLCHAIN/node_modules/aiot-toolkit/bin/index.js"
KEEP="$ROOT/.tmp/dist-keep"
VERSION="$(node -e "console.log(require('$ROOT/package.json').version)" 2>/dev/null || echo 1.0.0)"
PKG="com.vela.lyricplayer"

MODE="${1:-release}"
case "$MODE" in
  debug) CMD=build; LABEL=debug ;;
  release) CMD=release; LABEL=release ;;
  *) echo "用法: bash tools/build-rpk.sh [debug|release]" >&2; exit 1 ;;
esac

# 1. 准备工具链（首次运行约 8 分钟，之后复用）
if [ ! -f "$KIT" ]; then
  echo "[arclyric] 首次运行：安装 aiot-toolkit 1.2.1-alpha.0 到 .tmp/toolchain …"
  mkdir -p "$TOOLCHAIN"
  printf '{"name":"arclyric-toolchain","private":true}\n' > "$TOOLCHAIN/package.json"
  (
    cd "$TOOLCHAIN"
    PUPPETEER_SKIP_DOWNLOAD=1 PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=1 \
      npm install --no-bin-links --no-audit --no-fund aiot-toolkit@1.2.1-alpha.0
  )
fi

# 2. 编译器会清空 dist/，先把已有产物暂存
rm -rf "$KEEP"
mkdir -p "$KEEP"
for f in "$ROOT/dist"/*; do
  [ -f "$f" ] && cp "$f" "$KEEP"/
done

# 3. 构建
cd "$ROOT"
node "$KIT" "$CMD"

# 4. 还原被清掉的其它产物（APK / 另一签名的 RPK），不覆盖新产物
if compgen -G "$KEEP/*" > /dev/null; then
  cp -n "$KEEP"/* "$ROOT/dist"/ 2>/dev/null || true
fi
rm -rf "$KEEP"

# 4.5 交付说明跟着产物走，避免被编译器清空后丢失
if [ -f "$ROOT/tools/dist-README.md" ]; then
  cp "$ROOT/tools/dist-README.md" "$ROOT/dist/README.md"
fi

SRC="$(ls -t "$ROOT/dist/$PKG.$LABEL".*.rpk 2>/dev/null | head -1 || true)"
if [ -z "$SRC" ]; then
  echo "[arclyric] 构建失败：未找到 $PKG.$LABEL.*.rpk" >&2
  exit 1
fi

DEST="$ROOT/dist/Arclyric-$VERSION-$LABEL.rpk"
if [ "$SRC" != "$DEST" ]; then
  mv "$SRC" "$DEST"
fi

# 清掉编译器留下的原始命名附件（如有）
rm -f "$ROOT/dist/$PKG"".$LABEL"*.rpk

(
  cd "$ROOT/dist"
  shopt -s nullglob
  files=(./*.apk ./*.rpk)
  if [ ${#files[@]} -gt 0 ]; then
    sha256sum "${files[@]}" > SHA256SUMS.txt
  fi
)

echo
echo "[arclyric] 完成：$DEST ($(du -h "$DEST" | cut -f1))"
ls -lh "$ROOT/dist"
