#!/usr/bin/env python3
"""
离线瓦片包生成工具（零依赖，仅用标准库）。

把指定区域、指定缩放级的瓦片下载下来，打成 osmdroid 可直接读取的 zip。

zip 内路径**必须**是 {prefix}/{z}/{x}/{y}.png —— osmdroid 的 ZipFileArchive 用
tileSource.getTileRelativeFilenameString() 去查条目，也就是「源名/层级/列/行.png」。
少了 prefix 这一层，包会被静默忽略（不报错，只是查不到）。

prefix 必须与 App 里的 tile source 名完全一致：
  OSM 镜像         → OSM-Mirror
  天地图矢量底图   → TDT-vec
  天地图中文注记   → TDT-cva

用法：
  python tools/fetch_tiles.py --source tianditu --key <你的key> \
      --bbox 30.45,114.25,30.60,114.45 --zmin 10 --zmax 15 \
      --layer vec --out android/viewer/src/main/assets/TDT-vec.zip

  # OSM 镜像（无需 key，仅建议测试用）
  python tools/fetch_tiles.py --source osm \
      --bbox 30.45,114.25,30.60,114.45 --zmin 10 --zmax 13 \
      --out android/viewer/src/main/assets/OSM-Mirror.zip
"""

import argparse
import io
import math
import sys
import time
import urllib.request
import zipfile

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

# 天地图的「浏览器端」Key 会校验 Referer，缺这个头会返回 200 + 空/占位内容，
# 表现为「单张手测能取、批量全失败」。
REFERER = "http://localhost/"

source_is_tianditu = False


def lonlat_to_tile(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n)
    return max(0, min(n - 1, x)), max(0, min(n - 1, y))


def tile_url(source, key, layer, z, x, y):
    if source == "tianditu":
        host = f"https://t{(x + y) % 8}.tianditu.gov.cn"
        return (
            f"{host}/{layer}_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0"
            f"&LAYER={layer}&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles"
            f"&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk={key}"
        )
    return f"https://tile.openstreetmap.de/{z}/{x}/{y}.png"


def fetch(url, retries=3):
    for attempt in range(retries):
        try:
            req = urllib.request.Request(
                url, headers={"User-Agent": UA, "Referer": REFERER, "Accept": "image/*,*/*;q=0.8"}
            )
            with urllib.request.urlopen(req, timeout=20) as r:
                if r.status == 200:
                    data = r.read()
                    if data[:8] == b"\x89PNG\r\n\x1a\n" or data[:2] == b"\xff\xd8":
                        return data
                    # 天地图在 key 无效 / Referer 不合法时会返回 200 + 文本占位
                    if source_is_tianditu:
                        raise RuntimeError(
                            f"返回内容不是瓦片（{len(data)}B）—— 检查 Key 类型是否为「浏览器端」、配额是否用尽"
                        )
                    return data
                raise RuntimeError(f"HTTP {r.status}")
        except Exception as e:
            if attempt == retries - 1:
                print(f"    ! 放弃 {url[:80]}… {e}", file=sys.stderr)
                return None
            time.sleep(1.5 * (attempt + 1))
    return None


def main():
    global source_is_tianditu
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", choices=["tianditu", "osm"], default="tianditu")
    ap.add_argument("--key", default="", help="天地图 Key（source=tianditu 必填）")
    ap.add_argument("--layer", default="vec", help="天地图图层：vec 矢量底图 / cva 中文注记")
    ap.add_argument("--prefix", default="", help="zip 内顶层目录名，须等于 App 内 tile source 名；留空自动推断")
    ap.add_argument("--bbox", required=True, help="lat_min,lon_min,lat_max,lon_max")
    ap.add_argument("--zmin", type=int, default=10)
    ap.add_argument("--zmax", type=int, default=15)
    ap.add_argument("--out", required=True, help="输出 zip 路径")
    ap.add_argument("--sleep", type=float, default=0.05, help="每张间隔秒数，防止被限流")
    args = ap.parse_args()

    prefix = args.prefix or (
        f"TDT-{args.layer}" if args.source == "tianditu" else "OSM-Mirror"
    )

    source_is_tianditu = args.source == "tianditu"
    if source_is_tianditu and not args.key:
        sys.exit("source=tianditu 必须提供 --key（console.tianditu.gov.cn 免费申请）")

    lat_min, lon_min, lat_max, lon_max = (float(v) for v in args.bbox.split(","))

    jobs = []
    for z in range(args.zmin, args.zmax + 1):
        x0, y0 = lonlat_to_tile(lon_min, lat_max, z)
        x1, y1 = lonlat_to_tile(lon_max, lat_min, z)
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                jobs.append((z, x, y))

    print(f"瓦片源：{args.source}  缩放级：z{args.zmin}-z{args.zmax}  包内前缀：{prefix}/  合计 {len(jobs)} 张")

    ok = 0
    fail = 0
    total_bytes = 0
    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for i, (z, x, y) in enumerate(jobs, 1):
            url = tile_url(args.source, args.key, args.layer, z, x, y)
            data = fetch(url)
            if data:
                info = zipfile.ZipInfo(f"{prefix}/{z}/{x}/{y}.png", date_time=time.localtime()[:6])
                info.compress_type = zipfile.ZIP_DEFLATED
                zf.writestr(info, data)
                ok += 1
                total_bytes += len(data)
            else:
                fail += 1
            if i % 25 == 0 or i == len(jobs):
                print(f"  进度 {i}/{len(jobs)}  成功 {ok}  失败 {fail}")
            time.sleep(args.sleep)

    print(f"\n完成：{ok} 张入包，{fail} 张失败，原始 {total_bytes / 1024:.0f} KB → {args.out}")
    if ok == 0:
        sys.exit("一张都没成功，检查 Key / 网络 / bbox")


if __name__ == "__main__":
    main()
