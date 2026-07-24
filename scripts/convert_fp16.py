#!/usr/bin/env python3
"""
PP-OCRv6 ONNX 模型 FP32 → FP16 权重转换脚本(混合精度)。

策略:
- 只把模型权重从 FP32 转 FP16(减小文件体积约 50%)
- keep_io_types=True:输入/输出张量保持 FP32,避免运行时 cast 开销
- 激活值保持 FP32(混合精度),保证数值稳定性
- ORT 运行时会自动识别权重精度:ARMv8.2+ 用 FP16 计算,老设备 fallback FP32

用法:
    python3 scripts/convert_fp16.py
"""

import os
import sys
import time
from onnxconverter_common import float16
import onnx

ASSETS_DIR = "app/src/main/assets/ppocr"
MODELS = [
    ("PP-OCRv6_tiny_det.onnx", "PP-OCRv6_tiny_det_fp16.onnx"),
    ("PP-OCRv6_tiny_rec.onnx", "PP-OCRv6_tiny_rec_fp16.onnx"),
]


def convert(src_name: str, dst_name: str) -> None:
    src_path = os.path.join(ASSETS_DIR, src_name)
    dst_path = os.path.join(ASSETS_DIR, dst_name)

    if not os.path.exists(src_path):
        print(f"[SKIP] {src_path} not found")
        return

    src_size = os.path.getsize(src_path)
    print(f"[CONVERT] {src_path} ({src_size / 1024 / 1024:.2f} MB) -> {dst_path}")

    t0 = time.time()
    model = onnx.load(src_path)

    # 关键参数:
    # - keep_io_types=True: 保持输入/输出为 FP32(运行时无需改 Kotlin 代码,输入仍传 FP32)
    # - disable_shape_infer=False: 保留 shape 推断,便于调试
    # - op_block_list: 可选,屏蔽敏感算子(如 LayerNorm)保持 FP32;PP-OCRv6 默认全转即可
    model_fp16 = float16.convert_float_to_float16(
        model,
        keep_io_types=True,
        disable_shape_infer=False,
    )

    onnx.save(model_fp16, dst_path)
    dst_size = os.path.getsize(dst_path)
    elapsed = time.time() - t0

    ratio = (1 - dst_size / src_size) * 100
    print(
        f"  -> done in {elapsed:.1f}s. "
        f"Size: {src_size / 1024 / 1024:.2f} MB -> {dst_size / 1024 / 1024:.2f} MB "
        f"(-{ratio:.1f}%)"
    )


def main() -> int:
    print("=" * 60)
    print("PP-OCRv6 ONNX FP32 -> FP16 weight conversion")
    print("=" * 60)

    for src, dst in MODELS:
        convert(src, dst)

    print("\n[DONE] FP16 models generated. Next: replace assets & rebuild.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
