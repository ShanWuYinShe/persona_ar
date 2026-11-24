#!/usr/bin/env python3
"""
把交付目录里的动作精灵表导入 App 的 assets/animations/。

处理内容：
  1. 宽度补齐到能被 6 整除（Gemini 出的 3200 不能被 6 整除，补 4px 透明边即可，不重采样、不损画质）
  2. 文件名规范化（node.png -> nod.png 等别名）
  3. 等比例缩放到目标单帧宽度（控制 APK 体积）
  4. 输出到 android/app/src/main/assets/animations/<动作>.png

用法: python3 import_sprite_sheets.py <源目录> [--frame-width 480]
"""
import sys, os, struct, zlib, subprocess, shutil

FRAME_COUNT = 6

# 交付文件名 -> 动作名（支持常见笔误/别名）
ALIASES = {
    'node': 'nod',
    'nod': 'nod',
    'idle': 'idle',
    'wave': 'wave',
    'bow': 'bow',
    'yawn': 'yawn',
    'shy': 'shy',
    'blush': 'shy',
    'sit': 'sit',
    'shake': 'shake',
}


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
    assert ct == 6, f'需要 RGBA PNG，实际 colorType={ct}'
    raw = zlib.decompress(idat)
    bpp = 4; stride = w * 4; prev = bytearray(stride); rows = []
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
    return w, h, rows


def write_png(path, w, h, rows):
    raw = b''.join(b'\x00' + bytes(r) for r in rows)
    def chunk(t, data):
        return struct.pack('>I', len(data)) + t + data + struct.pack('>I', zlib.crc32(t + data) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)


def shrink_overflow_cells(path, frames, margin_px=4, max_shrink=0.12):
    """
    检测每格是否有像素碰到左右边界（人物溢出格子），若有则把整表等比缩小收进格子内。
    用 sips 做高质量重采样（而非手写最近邻，避免锯齿），再贴回原尺寸透明画布、底边对齐。
    只在真溢出时才缩，且最多缩 max_shrink。返回 (溢出格子列表, 缩放量)。
    """
    w, h, rows = load_png(path)
    cell = w // frames
    touched = []
    for k in range(frames):
        x0, x1 = k * cell, (k + 1) * cell
        L = sum(1 for y in range(h) for x in range(x0, x0 + margin_px) if rows[y][x*4+3] > 20)
        R = sum(1 for y in range(h) for x in range(x1 - margin_px, x1) if rows[y][x*4+3] > 20)
        if L > 5 or R > 5:
            touched.append((k, L, R))
    if not touched:
        return [], 0.0

    # 按最严重溢出估算需要的缩放比（保守取 1.5% 起步，最多 max_shrink）
    worst = max(max(L, R) for _, L, R in touched)
    est = min(max_shrink, 0.015 + worst / (h * 400))
    scale = 1.0 - est

    # 用 sips 高质量缩小到临时文件
    tmp = path + '.shrunk.png'
    new_w = int(w * scale)
    subprocess.run(['sips', '--resampleWidth', str(new_w), path, '--out', tmp],
                   check=True, capture_output=True)
    sw, sh, srows = load_png(tmp)
    os.remove(tmp)

    # 贴回原尺寸画布：水平居中、底边对齐
    new_rows = [bytearray(w * 4) for _ in range(h)]
    x_off = (w - sw) // 2
    y_off = h - sh
    for y in range(sh):
        new_rows[y_off + y][x_off*4:(x_off+sw)*4] = srows[y]
    write_png(path, w, h, new_rows)
    return touched, est


def pad_width_to_multiple(src, dst, frames):
    """右侧补透明列，使宽度能被 frames 整除"""
    w, h, rows = load_png(src)
    rem = w % frames
    if rem == 0:
        shutil.copy(src, dst)
        return w, h, 0
    pad = frames - rem
    new_w = w + pad
    new_rows = []
    for y in range(h):
        r = rows[y]
        new_rows.append(bytearray(r) + bytearray(pad * 4))
    write_png(dst, new_w, h, new_rows)
    return new_w, h, pad


def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    src_dir = sys.argv[1]
    # 单帧目标宽度；0 表示保持原始分辨率
    frame_width = 0
    if '--frame-width' in sys.argv:
        frame_width = int(sys.argv[sys.argv.index('--frame-width') + 1])

    out_dir = 'android/app/src/main/assets/animations'
    os.makedirs(out_dir, exist_ok=True)
    tmp_dir = '/tmp/personaar_import'
    os.makedirs(tmp_dir, exist_ok=True)

    imported, skipped = [], []
    for name in sorted(os.listdir(src_dir)):
        if not name.lower().endswith('.png'):
            continue
        stem = os.path.splitext(name)[0].lower()
        action = ALIASES.get(stem)
        if action is None:
            skipped.append((name, '未知动作名'))
            continue
        src_path = os.path.join(src_dir, name)

        # 1) 补宽度到 6 的倍数
        padded = os.path.join(tmp_dir, f'{action}_padded.png')
        w, h, pad = pad_width_to_multiple(src_path, padded, FRAME_COUNT)

        # 1.5) 溢出修正：若人物像素碰到格子左右边界（张臂/坐下时容易发生），
        #      把整表等比缩小收进格子内，避免渲染时头发/手臂被切掉一条
        touched, shrunk = shrink_overflow_cells(padded, FRAME_COUNT)
        if touched:
            print(f'  [{action}] 检测到 {len(touched)} 格人物溢出边界，整表内缩 {shrunk*100:.1f}%')

        # 2) 可选缩放：frame_width > 0 时按目标单帧宽度等比例缩放到该宽度。
        #    传 0 表示保持原始分辨率（不重采样）。
        if frame_width > 0:
            target_w = frame_width * FRAME_COUNT
            if w != target_w:
                resized = os.path.join(tmp_dir, f'{action}_resized.png')
                subprocess.run(['sips', '--resampleWidth', str(target_w), padded, '--out', resized],
                               check=True, capture_output=True)
                padded = resized
                w, h = load_png(padded)[0:2]

        out_path = os.path.join(out_dir, f'{action}.png')
        shutil.copy(padded, out_path)
        size_mb = os.path.getsize(out_path) / 1048576
        imported.append((action, name, w, h, pad, size_mb))

    print(f'源目录: {src_dir}')
    mode_desc = f'单帧目标宽度 {frame_width}px' if frame_width > 0 else '保持原始分辨率（不缩放）'
    print(f'输出:   {out_dir}  ({mode_desc})')
    print()
    print(f'{"动作":8s} {"源文件":10s} {"最终尺寸":14s} {"补宽":5s} {"体积":8s}')
    for action, name, w, h, pad, size_mb in imported:
        print(f'{action:8s} {name:10s} {w}x{h:<8d} +{pad:<4d} {size_mb:6.2f}MB')
    if skipped:
        print()
        print('跳过的文件:')
        for name, reason in skipped:
            print(f'  {name}: {reason}')
    print()
    print(f'已导入 {len(imported)} 个动作')


if __name__ == '__main__':
    main()