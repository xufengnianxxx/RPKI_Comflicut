#!/usr/bin/env python3
"""
根据 qidongjiaoben/测试结果-性能测试.md 中的实测数据生成答辩 PPT 用性能图。
依赖：Pillow（系统通常已带 python3-pil）

用法：
  python3 qidongjiaoben/scripts/generate_perf_chart.py
输出：
  qidongjiaoben/figures/图4-2-REST接口性能测试结果.png
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# 与《测试结果-性能测试.md》表 3.1 一致
SCENARIOS = [
    {"key": "health", "label": "健康检查\n/api/", "rps": 143.96, "p95_ms": 8.85, "avg_ms": 6.52},
    {"key": "certs", "label": "证书分页\n/certs", "rps": 146.44, "p95_ms": 104.26, "avg_ms": 64.52},
    {"key": "records", "label": "检测记录\n/pair-records", "rps": 257.31, "p95_ms": 71.39, "avg_ms": 37.32},
]

COLORS_RPS = ("#2563EB", "#0EA5E9", "#14B8A6")
COLORS_LAT = ("#F59E0B", "#EF4444", "#8B5CF6")

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "figures"
OUT_PNG = OUT_DIR / "图4-2-REST接口性能测试结果.png"

FONT_CJK = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
FONT_CJK_REG = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
FALLBACK = "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"


def load_font(path: str, size: int) -> ImageFont.FreeTypeFont:
    for p in (path, FONT_CJK_REG, FALLBACK):
        try:
            return ImageFont.truetype(p, size)
        except OSError:
            continue
    return ImageFont.load_default()


def draw_rounded_rect(draw: ImageDraw.ImageDraw, xy, radius: int, fill: str) -> None:
    x0, y0, x1, y1 = xy
    draw.rounded_rectangle(xy, radius=radius, fill=fill)


def draw_bar_chart(
    draw: ImageDraw.ImageDraw,
    area: tuple[int, int, int, int],
    values: list[float],
    labels: list[str],
    colors: tuple[str, ...],
    y_title: str,
    unit: str,
    font_title: ImageFont.FreeTypeFont,
    font_axis: ImageFont.FreeTypeFont,
    font_val: ImageFont.FreeTypeFont,
) -> None:
    x0, y0, x1, y1 = area
    pad_left, pad_bottom, pad_top, pad_right = 72, 88, 48, 24
    chart_x0 = x0 + pad_left
    chart_y0 = y0 + pad_top
    chart_x1 = x1 - pad_right
    chart_y1 = y1 - pad_bottom

    draw.text((x0 + 12, y0 + 8), y_title, fill="#1E293B", font=font_title)

    vmax = max(values) * 1.18
    n = len(values)
    bar_gap = 36
    bar_w = (chart_x1 - chart_x0 - bar_gap * (n + 1)) // n

    # 网格线
    for i in range(5):
        t = i / 4
        gy = int(chart_y1 - t * (chart_y1 - chart_y0))
        draw.line([(chart_x0, gy), (chart_x1, gy)], fill="#E2E8F0", width=1)
        tick = vmax * t
        draw.text((x0 + 8, gy - 10), f"{tick:.0f}", fill="#64748B", font=font_axis)

    for i, (v, lab, col) in enumerate(zip(values, labels, colors)):
        bx0 = chart_x0 + bar_gap + i * (bar_w + bar_gap)
        bx1 = bx0 + bar_w
        bh = int((v / vmax) * (chart_y1 - chart_y0))
        by1 = chart_y1
        by0 = by1 - bh
        draw_rounded_rect(draw, (bx0, by0, bx1, by1), 6, col)
        draw.text((bx0 + bar_w // 2 - 28, by0 - 28), f"{v:.1f}{unit}", fill="#334155", font=font_val)
        # 多行标签
        lines = lab.split("\n")
        for j, line in enumerate(lines):
            draw.text((bx0 + 4, chart_y1 + 10 + j * 22), line, fill="#475569", font=font_axis)

    draw.line([(chart_x0, chart_y1), (chart_x1, chart_y1)], fill="#94A3B8", width=2)
    draw.line([(chart_x0, chart_y0), (chart_x0, chart_y1)], fill="#94A3B8", width=2)


def main() -> None:
    w, h = 1600, 900
    img = Image.new("RGB", (w, h), "#FAFBFC")
    draw = ImageDraw.Draw(img)

    font_h1 = load_font(FONT_CJK, 34)
    font_sub = load_font(FONT_CJK_REG, 18)
    font_panel = load_font(FONT_CJK, 24)
    font_axis = load_font(FONT_CJK_REG, 16)
    font_val = load_font(FONT_CJK, 15)
    font_note = load_font(FONT_CJK_REG, 14)

    draw.text((48, 28), "RPKI 冲突检测系统 REST 接口性能测试结果", fill="#0F172A", font=font_h1)
    draw.text(
        (48, 78),
        "测试环境：Spring Boot 3.3 + MySQL 8 | 并发采样 | 成功率 100%",
        fill="#64748B",
        font=font_sub,
    )

    labels = [s["label"] for s in SCENARIOS]
    rps = [s["rps"] for s in SCENARIOS]
    p95 = [s["p95_ms"] for s in SCENARIOS]

    # 左：吞吐量；右：P95 时延
    draw_bar_chart(
        draw,
        (40, 120, w // 2 - 20, h - 100),
        rps,
        labels,
        COLORS_RPS,
        "吞吐量 (req/s)",
        "",
        font_panel,
        font_axis,
        font_val,
    )
    draw_bar_chart(
        draw,
        (w // 2 + 20, 120, w - 40, h - 100),
        p95,
        labels,
        COLORS_LAT,
        "P95 响应时延 (ms)",
        "",
        font_panel,
        font_axis,
        font_val,
    )

    # 图例说明条
    note_y = h - 72
    draw.rounded_rectangle((48, note_y, w - 48, h - 28), radius=8, fill="#F1F5F9", outline="#CBD5E1")
    draw.text(
        (64, note_y + 12),
        "结论：三类查询接口在实测负载下均 100% 成功；检测记录接口吞吐最高(257 req/s)，"
        "证书分页 P95 时延相对较高(104 ms)，符合查询字段更重的业务特征。",
        fill="#475569",
        font=font_note,
    )

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    img.save(OUT_PNG, "PNG", dpi=(150, 150))
    print(f"已生成: {OUT_PNG}")


if __name__ == "__main__":
    main()
