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
    frame_width = 480
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

        # 2) 等比例缩放到目标单帧宽
        target_w = frame_width * FRAME_COUNT
        if w != target_w:
            resized = os.path.join(tmp_dir, f'{action}_resized.png')
            subprocess.run(['sips', '--resampleWidth', str(target_w), padded, '--out', resized],
                           check=True, capture_output=True)
            padded = resized
            w = target_w
            _, h, _ = load_png(padded)[0:3] if False else (w, load_png(padded)[1], 0)

        out_path = os.path.join(out_dir, f'{action}.png')
        shutil.copy(padded, out_path)
        size_mb = os.path.getsize(out_path) / 1048576
        imported.append((action, name, w, h, pad, size_mb))

    print(f'源目录: {src_dir}')
    print(f'输出:   {out_dir}  (单帧目标宽度 {frame_width}px)')
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