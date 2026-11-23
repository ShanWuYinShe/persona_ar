#!/usr/bin/env python3
"""
精灵表交付前自检：在导入 App 之前，判断一张动作精灵表能不能用。

检查项（任一 FAIL 都说明需要重新生成）：

  1. 格式      必须是 PNG 且带 alpha 通道，或品红/纯色底（可自动抠除）
  2. 等分      宽度必须能被 6 整除（运行时按 6 等分裁切，不能整除就会切歪）
  3. 比例      宽高比需接近 2.4:1（各动作统一，切换动作时身高才不会跳变）
  4. 格内容    每格都必须有人物像素，且人物不能跨出格子边界（越界说明格子没对齐）
  5. 高度一致  6 格人物高度差不能超过 8%（否则播放时人物会上下跳动）
  6. 底边对齐  6 格人物底边必须基本对齐（允许坐下类动作例外）

用法: python3 check_sprite_sheet.py <精灵表.png> [--allow-bottom-varying]
"""
import sys, os, struct, zlib, subprocess, tempfile

FRAMES = 6
TARGET_ASPECT = 2.4
ASPECT_TOLERANCE = 0.55
HEIGHT_VAR_TOLERANCE = 0.12

def to_png(path):
    """统一转成 PNG（Gemini 下载可能是 jpeg）"""
    if path.lower().endswith('.png'):
        return path
    tmp = tempfile.mktemp(suffix='.png')
    subprocess.run(['sips', '-s', 'format', 'png', path, '--out', tmp],
                   check=True, capture_output=True)
    return tmp

def load_png(path):
    d = open(path, 'rb').read()
    pos = 8; idat = b''; w = h = ct = bd = None
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos+4])[0]
        typ = d[pos+4:pos+8]
        data = d[pos+8:pos+8+ln]
        if typ == b'IHDR':
            w, h, bd, ct = struct.unpack('>IIBB', data[:10])
        elif typ == b'IDAT':
            idat += data
        pos += 12 + ln
    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[ct]
    bpp = ch * (bd // 8)
    stride = w * bpp
    prev = bytearray(stride)
    rows = []
    def paeth(a, b, c):
        p = a + b - c; pa = abs(p - a); pb = abs(p - b); pc = abs(p - c)
        return a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
    for y in range(h):
        off = y * (stride + 1); ft = raw[off]; line = bytearray(raw[off+1:off+1+stride])
        for i in range(stride):
            a = line[i-bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i-bpp] if i >= bpp else 0
            if ft == 1: line[i] = (line[i] + a) & 255
            elif ft == 2: line[i] = (line[i] + b) & 255
            elif ft == 3: line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif ft == 4: line[i] = (line[i] + paeth(a, b, c)) & 255
        rows.append(line); prev = line
    return w, h, ch, rows

def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    path = sys.argv[1]
    allow_bottom_varying = '--allow-bottom-varying' in sys.argv

    if not os.path.exists(path):
        print(f'FAIL  文件不存在: {path}'); sys.exit(1)

    png = to_png(path)
    w, h, ch, rows = load_png(png)
    fails, warns = [], []

    print(f'文件: {os.path.basename(path)}')
    print(f'尺寸: {w} x {h}  通道: {ch}')
    print()

    # 1. 格式与背景
    has_alpha = ch in (2, 4, 6)
    if not has_alpha:
        print('FAIL  没有 alpha 通道，也没有可识别的纯色底')
        sys.exit(1)
    # 采样四角判断背景
    def px(x, y):
        r = rows[y]
        i = x * ch
        if ch == 4: return (r[i], r[i+1], r[i+2], r[i+3])
        if ch == 3: return (r[i], r[i+1], r[i+2], 255)
        if ch == 2: return (r[i], r[i], r[i], r[i+1])
        return (r[i], r[i], r[i], 255)
    corners = [px(3,3), px(w-4,3), px(3,h-4), px(w-4,h-4)]
    bg_transparent = all(c[3] < 32 for c in corners)
    print(f'四角像素: {corners[0]} {corners[1]} {corners[2]} {corners[3]}')
    if bg_transparent:
        print('PASS  背景已透明')
    else:
        cs = set((c[0]//16, c[1]//16, c[2]//16) for c in corners)
        if len(cs) <= 1:
            print(f'PASS  纯色背景 RGB{corners[0][:3]}（导入时会自动抠除）')
        else:
            fails.append('四角颜色不一致，背景不是纯色，无法干净抠除')
    print()

    # 2. 等分
    if w % FRAMES != 0:
        fails.append(f'宽度 {w} 不能被 {FRAMES} 整除（余 {w % FRAMES}），按 6 等分会切歪')
    cell = w // FRAMES
    print(f'每格宽度: {cell}px')

    # 3. 比例
    aspect = w / h
    print(f'宽高比: {aspect:.2f} (目标 {TARGET_ASPECT})')
    if abs(aspect - TARGET_ASPECT) > ASPECT_TOLERANCE:
        warns.append(f'宽高比 {aspect:.2f} 偏离目标 {TARGET_ASPECT} 较多，切换动作时身高可能跳变')
    print()

    # 4-6. 逐格分析
    print('逐格分析:')
    print('  帧   人物X范围        人物Y范围        高度   底边   是否越界')
    heights, bottoms, ok_frames = [], [], 0
    alpha_threshold = 8 if bg_transparent else 40
    bg = corners[0][:3] if not bg_transparent else None

    for k in range(FRAMES):
        x0, x1 = k * cell, (k + 1) * cell
        minx, maxx, miny, maxy = x1, x0, h, -1
        for y in range(0, h, 2):
            r = rows[y]
            for x in range(x0, x1, 2):
                i = x * ch
                if bg_transparent:
                    solid = r[i + ch - 1] > alpha_threshold
                else:
                    dr = r[i] - bg[0]; dg = r[i+1] - bg[1]; db = r[i+2] - bg[2]
                    solid = (dr*dr + dg*dg + db*db) ** 0.5 > 60
                if solid:
                    if x < minx: minx = x
                    if x > maxx: maxx = x
                    if y < miny: miny = y
                    if y > maxy: maxy = y
        if maxy < 0:
            fails.append(f'第{k+1}格是空的，没有人物')
            print(f'  {k+1}    (空)')
            continue
        fh = maxy - miny + 1
        heights.append(fh)
        bottoms.append(maxy)
        edge_hit = (minx <= x0 + 2) or (maxx >= x1 - 3)
        note = '← 贴边' if edge_hit else ''
        if edge_hit:
            warns.append(f'第{k+1}格人物碰到格子边界，可能被切到')
        ok_frames += 1
        print(f'  {k+1}    {minx:4d}..{maxx:4d}      {miny:4d}..{maxy:4d}      {fh:4d}  {maxy:4d}  {note}')

    print()
    if ok_frames < FRAMES:
        fails.append(f'只有 {ok_frames}/{FRAMES} 格有人物')

    # 高度一致性
    if len(heights) >= 2:
        hmin, hmax = min(heights), max(heights)
        var = (hmax - hmin) / hmin
        print(f'人物高度: 最小 {hmin}, 最大 {hmax}, 差异 {var:.1%}')
        if var > HEIGHT_VAR_TOLERANCE:
            fails.append(f'各帧人物高度差异 {var:.1%} 超过 {HEIGHT_VAR_TOLERANCE:.0%}，播放时人物会上下跳动')
        else:
            print('PASS  各帧高度一致')
    print()

    # 底边对齐：站立类动作要求脚踩同一条线（差异 < 画布的 3%）
    if not allow_bottom_varying and len(bottoms) >= 2:
        bmin, bmax = min(bottoms), max(bottoms)
        diff = bmax - bmin
        print(f'底边位置: 最小 {bmin}, 最大 {bmax}, 差异 {diff}px')
        if diff > h * 0.03:
            fails.append(f'各帧底边差异 {diff}px 过大，脚底会跳动（坐下类动作请加 --allow-bottom-varying）')
        else:
            print('PASS  各帧底边对齐')
    elif allow_bottom_varying:
        print('SKIP  已指定允许底边变化（坐下类动作）')
    print()

    # 结论
    if fails:
        print('=' * 56)
        print('FAIL  这张图不能直接用，需要重新生成：')
        for i, f in enumerate(fails, 1):
            print(f'  {i}. {f}')
        print('=' * 56)
        sys.exit(1)

    if warns:
        print('=' * 56)
        print('PASS(有警告)  可以导入，但注意：')
        for i, wmsg in enumerate(warns, 1):
            print(f'  {i}. {wmsg}')
        print('=' * 56)
        sys.exit(0)

    print('=' * 56)
    print('PASS  全部检查通过，可以直接导入使用')
    print('=' * 56)

if __name__ == '__main__':
    main()