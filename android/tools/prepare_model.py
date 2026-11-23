#!/usr/bin/env python3
"""
将 VRM（带 VRM 扩展的 glTF 2.0 GLB）转换为 PersonaAR 可直接用 Filament gltfio 加载的 PBR 手办 GLB。

变换内容：
  1. 剥离材质上的 KHR_materials_unlit（MToon 惯例，防止标准 glTF 查看器对已烘焙明暗的贴图双重打光），
     让 Filament 用真实 PBR 光照渲染（迎光面高光随光源流动）；仅眼睛材质（名称含 EYE）保留 unlit，
     以维持手绘虹膜的锐利度。
  2. 删除 extensions.VRM（表情 / 弹簧骨 / MToon 定义，对 Filament 是死数据）。
  3. 可达性分析：删除不再被任何材质引用的 texture / image（自然剔除 Thumbnail 与 Shader_None* 等孤儿）。
  4. 重建 BIN chunk：只保留仍被 accessor / image 引用的 bufferView，紧凑排列并重新映射索引。

仅依赖 Python 3 标准库。用法：python3 prepare_model.py <输入.vrm|.glb> <输出.glb>
"""
import json
import struct
import sys


def read_glb(path):
    data = open(path, "rb").read()
    magic, version, length = struct.unpack_from("<4sII", data, 0)
    assert magic == b"glTF" and version == 2, "不是 glTF 2.0 GLB"
    assert length == len(data), "头部长度 %d != 文件长度 %d" % (length, len(data))
    off = 12
    js, bin_ = None, b""
    while off < length:
        clen, ctype = struct.unpack_from("<I4s", data, off)
        off += 8
        payload = data[off:off + clen]
        off += clen
        if ctype == b"JSON":
            js = json.loads(payload.decode("utf-8").rstrip(" \x00"))
        elif ctype == b"BIN\x00":
            bin_ = payload
    assert js is not None, "缺少 JSON chunk"
    return js, bin_


def write_glb(path, js, bin_):
    jb = json.dumps(js, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    jb += b" " * (-len(jb) % 4)
    bb = bin_ + b"\x00" * (-len(bin_) % 4)
    total = 12 + 8 + len(jb) + (8 + len(bb) if bin_ else 0)
    out = bytearray()
    out += struct.pack("<4sII", b"glTF", 2, total)
    out += struct.pack("<I4s", len(jb), b"JSON") + jb
    if bin_:
        out += struct.pack("<I4s", len(bb), b"BIN\x00") + bb
    open(path, "wb").write(out)
    return total


def for_each_texture_ref(node, fn):
    """递归遍历 material 内所有以 Texture 结尾的键，对其 {index} 字典执行 fn。"""
    if isinstance(node, dict):
        for k, v in node.items():
            if k.endswith("Texture") and isinstance(v, dict) and "index" in v:
                fn(v)
            else:
                for_each_texture_ref(v, fn)
    elif isinstance(node, list):
        for x in node:
            for_each_texture_ref(x, fn)


def transform(js, bin_):
    materials = js.get("materials", [])

    # 1/2. 材质级：剥离 unlit（眼睛例外）与 VRM 扩展
    any_unlit_left = False
    for m in materials:
        ext = m.get("extensions")
        if not ext:
            continue
        is_eye = "EYE" in m.get("name", "").upper()
        if "KHR_materials_unlit" in ext:
            if is_eye:
                any_unlit_left = True
            else:
                del ext["KHR_materials_unlit"]
        if "VRM" in ext:
            del ext["VRM"]
        if not ext:
            del m["extensions"]

    # 2. 顶层 VRM 扩展（meta / humanoid / blendShape / secondary）
    top_ext = js.get("extensions")
    if top_ext and "VRM" in top_ext:
        del top_ext["VRM"]
        if not top_ext:
            del js["extensions"]

    for key in ("extensionsUsed", "extensionsRequired"):
        if key in js:
            lst = [e for e in js[key] if e != "VRM"]
            if not any_unlit_left:
                lst = [e for e in lst if e != "KHR_materials_unlit"]
            if lst:
                js[key] = lst
            else:
                del js[key]

    # 3. 可达性：只保留仍被材质引用的 texture / image
    textures = js.get("textures", [])
    images = js.get("images", [])
    used_tex = set()
    for m in materials:
        for_each_texture_ref(m, lambda d: used_tex.add(d["index"]))
    used_img = {textures[t]["source"] for t in used_tex if "source" in textures[t]}
    tex_remap = {old: new for new, old in enumerate(sorted(used_tex))}
    img_remap = {old: new for new, old in enumerate(sorted(used_img))}
    for m in materials:
        for_each_texture_ref(m, lambda d: d.__setitem__("index", tex_remap[d["index"]]))
    js["textures"] = [textures[i] for i in sorted(used_tex)]
    js["images"] = [images[i] for i in sorted(used_img)]
    for t in js["textures"]:
        if "source" in t:
            t["source"] = img_remap[t["source"]]

    # 4. 重建 BIN：只保留被 accessor / image 引用的 bufferView
    accessors = js.get("accessors", [])
    buffer_views = js.get("bufferViews", [])
    used_bv = {a["bufferView"] for a in accessors if "bufferView" in a}
    used_bv |= {im["bufferView"] for im in js["images"] if "bufferView" in im}
    new_bin = bytearray()
    bv_remap = {}
    new_bvs = []
    for old in sorted(used_bv):
        bv = buffer_views[old]
        new_bin += b"\x00" * (-len(new_bin) % 4)
        start = bv.get("byteOffset", 0)
        chunk = bin_[start:start + bv["byteLength"]]
        assert len(chunk) == bv["byteLength"], "bufferView %d 越界" % old
        bv_remap[old] = len(new_bvs)
        nbv = dict(bv)
        nbv["byteOffset"] = len(new_bin)
        new_bvs.append(nbv)
        new_bin += chunk
    new_bin += b"\x00" * (-len(new_bin) % 4)
    for a in accessors:
        if "bufferView" in a:
            a["bufferView"] = bv_remap[a["bufferView"]]
    for im in js["images"]:
        if "bufferView" in im:
            im["bufferView"] = bv_remap[im["bufferView"]]
    js["bufferViews"] = new_bvs
    if js.get("buffers"):
        js["buffers"][0]["byteLength"] = len(new_bin)
    return bytes(new_bin)


def verify(path):
    js, bin_ = read_glb(path)

    def is_eye(m):
        return "EYE" in m.get("name", "").upper()

    bad = [m.get("name") for m in js["materials"] if not is_eye(m)
           and "KHR_materials_unlit" in m.get("extensions", {})]
    assert not bad, "仍有非眼睛材质带 unlit: %s" % bad
    assert "VRM" not in js.get("extensionsUsed", []), "extensionsUsed 仍含 VRM"
    assert "VRM" not in js.get("extensions", {}), "顶层 VRM 扩展仍存在"
    assert not any(im.get("name") == "Thumbnail" for im in js.get("images", [])), "Thumbnail 未删除"
    nbv = len(js["bufferViews"])
    for a in js.get("accessors", []):
        if "bufferView" in a:
            assert a["bufferView"] < nbv, "accessor bufferView 越界"
    for im in js.get("images", []):
        if "bufferView" in im:
            assert im["bufferView"] < nbv, "image bufferView 越界"
    assert len(bin_) == js["buffers"][0]["byteLength"], "BIN 长度与 buffers[0].byteLength 不一致"
    for bv in js["bufferViews"]:
        assert bv["byteOffset"] + bv["byteLength"] <= len(bin_), "bufferView 越界"
    for m in js["materials"]:
        for_each_texture_ref(m, lambda d: (_ for _ in ()).throw(AssertionError("texture 索引越界"))
                             if d["index"] >= len(js["textures"]) else None)
    return js, bin_


def summary(tag, js, n):
    print("[%s] bytes=%d materials=%d textures=%d images=%d meshes=%d nodes=%d skins=%d bufferViews=%d" % (
        tag, n, len(js.get("materials", [])), len(js.get("textures", [])), len(js.get("images", [])),
        len(js.get("meshes", [])), len(js.get("nodes", [])), len(js.get("skins", [])),
        len(js.get("bufferViews", []))))


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    src, dst = sys.argv[1], sys.argv[2]
    js, bin_ = read_glb(src)

    # 许可证：从源文件 VRM meta 原样读出（第一手来源），供操作者核对后再入库
    meta = (js.get("extensions", {}).get("VRM") or {}).get("meta", {})
    if meta:
        print("[源文件 VRM meta 许可证字段]")
        for k in ("title", "author", "allowedUserName", "commercialUssageName",
                  "modificationPermissionName", "licenseName", "otherLicenseUrl"):
            if k in meta:
                print("  %s: %s" % (k, meta[k]))

    summary("变换前", js, len(open(src, "rb").read()))
    new_bin = transform(js, bin_)
    write_glb(dst, js, new_bin)
    js2, _ = verify(dst)
    summary("变换后", js2, len(open(dst, "rb").read()))
    print("[自检通过]", dst)


if __name__ == "__main__":
    main()
