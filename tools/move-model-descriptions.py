#!/usr/bin/env python3
"""Mueve las descripciones del catálogo de ModelDownloader.kt a strings.xml (es/en/zh)."""
import re, sys, pathlib

EN = [
 "Google Gemma 2 2B - Fast and efficient, solid overall quality",
 "Google Gemma 2 2B - Higher quality, uses more memory",
 "Google Gemma 3n E4B - Multilingual and efficient (text)",
 "Microsoft Phi-3 - Very efficient, 4K context",
 "Microsoft Phi-3.5 - Improved, 128K context",
 "Meta Llama 3.2 1B - Ultra light, ideal for phones",
 "Meta Llama 3.2 3B - Balance between quality and size",
 "Alibaba Qwen 2.5 - Strong in Chinese and English, very efficient",
 "Alibaba Qwen 2.5 3B - Better quality, multilingual",
 "Alibaba Qwen 2.5 - The smallest, starts instantly",
 "HuggingFace SmolLM2 - Fast and good at English",
 "DeepSeek - Step-by-step reasoning, very light",
 "Alibaba Qwen 3 - Hybrid reasoning and multilingual",
 "Google Gemma 3 - Good quality/speed balance",
 "Microsoft Phi-4 Mini - Strong at maths and code",
 "Mistral AI - Versatile classic, good writing",
 "Alibaba Qwen 2.5 7B - Excellent in Chinese and English",
 "Alibaba Qwen 2.5 Coder - Specialised in programming",
 "Meta Llama 3.1 8B - The best quality on the list",
 "Alibaba Qwen 2.5 - Optimised LiteRT-LM format (GPU/NPU)",
 "DeepSeek - Step-by-step reasoning in LiteRT-LM",
 "Microsoft Phi-4 Mini - LiteRT-LM, good at maths",
 "Alibaba Qwen 2.5 - Full precision (needs about 7 GB of RAM)",
 "Alibaba Qwen 3 - The smallest LiteRT, with reasoning",
 "Liquid AI - Built to reason, very compact",
 "OpenBMB MiniCPM5 - Compact and multilingual",
 "IBM Granite 4.0 - Focused on enterprise and code",
 "Alibaba Qwen 3 4B - Hybrid reasoning in LiteRT",
 "Mistral AI - The most capable LiteRT (needs about 6 GB of RAM)",
 "Vision projector for Qwen2-VL 2B (llama.cpp)",
 "SD 1.5 pruned emaonly - the lightest for mobile",
 "SD 1.5 pruned emaonly - more quality than Q4, uses more RAM",
 "SD 2.1 768px - another classic base, single file",
 "SDXL realistic - good quality in 1.4 GB (recommended on mobile)",
 "One-step LoRA: image in one step with the LCM/TCD sampler · 257 MB",
 "fastsdcpu-style distillation: image in 1-4 steps, ideal on CPU",
 "SDXL in 2 steps - the fastest to experiment with",
 "Danbooru WD v1.4 tagger - also needs the CSV list",
 "selected_tags.csv tag list for the WD14 tagger",
]

ZH = [
 "Google Gemma 2 2B - 快速高效，整体质量不错",
 "Google Gemma 2 2B - 质量更高，占用更多内存",
 "Google Gemma 3n E4B - 多语言且高效（文字）",
 "Microsoft Phi-3 - 非常高效，4K 上下文",
 "Microsoft Phi-3.5 - 改进版，128K 上下文",
 "Meta Llama 3.2 1B - 超轻量，手机首选",
 "Meta Llama 3.2 3B - 质量与体积的平衡",
 "Alibaba Qwen 2.5 - 中英文俱佳，非常高效",
 "Alibaba Qwen 2.5 3B - 质量更好，多语言",
 "Alibaba Qwen 2.5 - 体积最小，秒启动",
 "HuggingFace SmolLM2 - 速度快，英文表现好",
 "DeepSeek - 逐步推理，非常轻量",
 "Alibaba Qwen 3 - 混合推理，多语言",
 "Google Gemma 3 - 质量与速度平衡良好",
 "Microsoft Phi-4 Mini - 数学与代码见长",
 "Mistral AI - 经典通用，写作出色",
 "Alibaba Qwen 2.5 7B - 中英文表现优异",
 "Alibaba Qwen 2.5 Coder - 专注编程",
 "Meta Llama 3.1 8B - 列表中质量最高",
 "Alibaba Qwen 2.5 - 优化的 LiteRT-LM 格式（GPU/NPU）",
 "DeepSeek - LiteRT-LM 中的逐步推理",
 "Microsoft Phi-4 Mini - LiteRT-LM，数学出色",
 "Alibaba Qwen 2.5 - 完整精度（需要约 7 GB 内存）",
 "Alibaba Qwen 3 - 最小的 LiteRT，带推理能力",
 "Liquid AI - 为推理而生，非常紧凑",
 "OpenBMB MiniCPM5 - 紧凑且多语言",
 "IBM Granite 4.0 - 面向企业与代码",
 "Alibaba Qwen 3 4B - LiteRT 中的混合推理",
 "Mistral AI - 最强的 LiteRT（需要约 6 GB 内存）",
 "Qwen2-VL 2B 的视觉投影器（llama.cpp）",
 "SD 1.5 pruned emaonly — 手机上最轻量",
 "SD 1.5 pruned emaonly — 比 Q4 质量更高，占用更多内存",
 "SD 2.1 768px — 另一款经典底座，单文件",
 "SDXL 写实风格 — 1.4 GB 内质量出色（手机推荐）",
 "一步 LoRA：配合 LCM/TCD 采样器一步出图 · 257 MB",
 "fastsdcpu 风格蒸馏：1-4 步出图，适合 CPU",
 "SDXL 两步出图 — 试玩最快",
 "Danbooru WD v1.4 打标器 — 还需要 CSV 列表",
 "WD14 打标器用的 selected_tags.csv 标签列表",
]

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
kt = root / "app/src/main/java/com/yumenode/ai/ModelDownloader.kt"
src = kt.read_text(encoding="utf-8")

# 1. Español: el texto actual del catálogo, en orden de aparición.
es = re.findall(r'description = "([^"]*)"', src)
assert len(es) == len(EN) == len(ZH), f"recuentos: es={len(es)} en={len(EN)} zh={len(ZH)}"

# 2. Sustituir por referencias a recursos.
it = iter(range(1, len(es) + 1))
src2, n = re.subn(r'description = "[^"]*"',
                  lambda m: f"descriptionRes = R.string.md_{next(it):03d}", src)
assert n == len(es), n
src2 = src2.replace("        val description: String,",
                    "        /** Descripción traducida (ver res/values*/strings.xml). */\n        val descriptionRes: Int,", 1)
kt.write_text(src2, encoding="utf-8")

# 3. Añadir las cadenas a los tres idiomas.
def esc(t: str) -> str:
    return t.replace("&", "&amp;").replace("'", "\\'").replace('"', '\\"')

def append(loc: str, values: list[str]) -> int:
    p = root / f"app/src/main/res/values{loc}/strings.xml"
    s = p.read_text(encoding="utf-8")
    have = set(re.findall(r'<string name="([^"]+)"', s))
    lines = [f'    <string name="md_{i:03d}">{esc(v)}</string>'
             for i, v in enumerate(values, 1) if f"md_{i:03d}" not in have]
    if lines:
        s = s.replace("</resources>", "\n".join(lines) + "\n</resources>")
        p.write_text(s, encoding="utf-8")
    return len(lines)

print("kt: descripciones sustituidas:", n)
print("values:", append("", EN), " values-es:", append("-es", es), " values-zh:", append("-zh", ZH))
