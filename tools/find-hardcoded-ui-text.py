#!/usr/bin/env python3
"""Busca literales con pinta de texto de UI que no estén en strings.xml."""
import re, pathlib, sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
kt = sorted((root / "app/src/main/java/com/yumenode/ai").glob("*.kt"))

# Líneas que son claramente diagnóstico o datos, no UI.
SKIP_CODE = re.compile(
    r"(Log\.[dweiv]|Trace\.|\bTAG\b|require\(|check\(|error\(|Exception|"
    r"throw |println|System\.out|Content-Type|Accept|Authorization|Bearer|"
    r"user-agent|User-Agent|application/|\bjson\b|JSON|\bhttp|://|"
    r"\.json|\.txt|\.gguf|\.litertlm|\.bin|\.csv|\.apk|"
    r"^\"s_|key\b.*=|onValueChanged)"
)
# Literales con pinta de frase para el usuario.
PHRASE = re.compile(r"^[\"'`]([A-Za-zÁÉÍÓÚÜÑáéíóúüñ¿¡][^\"'`]{7,})[\"'`]")

out = []
for f in kt:
    for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        code = line.split("//")[0] if "//" in line and "://" not in line else line
        if SKIP_CODE.search(code):
            continue
        for m in re.finditer(r'"([^"\\$]{8,})"', code):
            t = m.group(1)
            if not re.search(r"[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]{3,}", t):
                continue
            if " " not in t.strip():          # una sola palabra: casi siempre técnica
                continue
            if re.fullmatch(r"[a-z0-9_.\-]+", t):  # claves, ids, slugs
                continue
            out.append((f.name, i, t))

print(f"# {len(out)} literales candidatos\n")
for name, i, t in out:
    print(f"{name}:{i}\t{t}")
