# Modelos disponibles en el catálogo

## GGUF (llama.cpp) - Chat y razonamiento

### Modelos ligeros (ideal para probar rápido)
- **Llama 3.2 1B Instruct Q4_K_M** (807 MB) - Meta Llama 3.2 1B - Ultra ligero para móviles
- **Qwen 2.5 0.5B Instruct Q4_K_M** (398 MB) - Alibaba Qwen 2.5 - El más pequeño, arranca al instante
- **Qwen 2.5 1.5B Instruct Q4_K_M** (1.1 GB) - Alibaba Qwen 2.5 - Fuerte en chino/inglés
- **SmolLM2 1.7B Instruct Q4_K_M** (1.0 GB) - HuggingFace SmolLM2 - Rápido y bueno en inglés
- **DeepSeek R1 Distill Qwen 1.5B Q4_K_M** (1.1 GB) - DeepSeek - Razonamiento paso a paso, muy ligero

### Modelos medianos (balance calidad/prueba)
- **Llama 3.2 3B Instruct Q4_K_M** (2.0 GB) - Meta Llama 3.2 3B
- **Gemma 2 2B Instruct Q4_K_M** (1.7 GB) - Google Gemma 2 2B - Eficiente y rápido
- **Gemma 2 2B Instruct Q8_0** (2.8 GB) - Mayor calidad
- **Qwen 2.5 3B Instruct Q4_K_M** (2.1 GB) - Alibaba Qwen 2.5 3B - Mejor calidad, multilingüe
- **Gemma 3 4B IT Q4_K_M** (2.5 GB) - Google Gemma 3 - Buen equilibrio calidad/velocidad
- **Phi-4 Mini Instruct Q4_K_M** (2.5 GB) - Microsoft Phi-4 Mini - Fuerte en matemáticas y código

### Modelos con razonamiento (Thinking mode)
- **DeepSeek R1 Distill Qwen 1.5B Q4_K_M** (1.1 GB) - DeepSeek - Razonamiento paso a paso
- **Qwen 3 4B Q4_K_M** (2.5 GB) - Alibaba Qwen 3 - Razonamiento híbrido
- **Qwen 3 0.6B LiteRT** (614 MB) - Alibaba Qwen 3 - El LiteRT más pequeño, con razonamiento
- **TwIL-8B Q4_K_M** (5.3 GB) - WebAI TwIL-8B - Razonamiento paso a paso, 8B params ⭐ NUEVO
- **LFM 2.5 1.2B Thinking LiteRT** (736 MB) - Liquid AI - Diseñado para razonar, muy compacto

### Modelos grandes (necesitan 6-8 GB de RAM libre)
- **Mistral 7B Instruct v0.3 Q4_K_M** (4.4 GB) - Mistral AI - Clásico versátil
- **Qwen 2.5 7B Instruct Q4_K_M** (4.7 GB) - Alibaba Qwen 2.5 7B - Excelente en chino/inglés
- **Qwen 2.5 Coder 7B Instruct Q4_K_M** (4.7 GB) - Alibaba Qwen 2.5 Coder - Especializado en programación
- **Llama 3.1 8B Instruct Q4_K_M** (4.9 GB) - Meta Llama 3.1 8B - La mejor calidad
- **Phi-4 Mini LiteRT Q8** (3.9 GB) - Microsoft Phi-4 Mini - LiteRT-LM

### LiteRT-LM (optimizado GPU/NPU)
- **Qwen 2.5 1.5B LiteRT Q8** (1.6 GB) - Alibaba Qwen 2.5 - Formato LiteRT-LM optimizado
- **Qwen 2.5 1.5B LiteRT F32** (6.2 GB) - Precisión completa (necesita ~7 GB de RAM)
- **DeepSeek R1 Distill 1.5B LiteRT Q8** (1.8 GB) - DeepSeek - Razonamiento paso a paso
- **Phi-4 Mini LiteRT Q8** (3.9 GB) - Microsoft Phi-4 Mini - Bueno en matemáticas
- **Ministral 3 8B LiteRT** (5.0 GB) - Mistral AI - El LiteRT más capaz (necesita ~6 GB RAM)

### Modelos grandes (6-8 GB RAM)
- **Granite 4.0 Micro LiteRT** (2.2 GB) - IBM Granite 4.0 - Enfocado en empresa y código
- **Qwen 3 4B LiteRT** (2.7 GB) - Alibaba Qwen 3 4B - Razonamiento híbrido

## Modelos de dibujo (Stable Diffusion)
- **Stable Diffusion 1.5 Q4_0** (1.5 GB) - SD 1.5 pruned emaonly - Más ligero
- **Stable Diffusion 1.5 Q8_0** (1.8 GB) - Mayor calidad, pide más RAM
- **Stable Diffusion 2.1 Q4_0** (1.7 GB) - SD 2.1 768px
- **RealVisXL V5.0 Q4_0** (1.5 GB) - SDXL realista - Recomendado en móvil
- **SDXL Lightning 2-step Q4_0** (2.6 GB) - SDXL en 2 pasos - El más rápido
- **SD Turbo Q8_0** (2.0 GB) - Imagen en 1-4 pasos, ideal en CPU

## Proyectores de visión
- **mmproj Qwen2-VL 2B f16** (1.3 GB) - Para modelos Qwen2-VL

## Etiquetadores
- **WD14 Moat Tagger (ONNX)** (326 MB) - Etiquetador Danbooru
- **WD14 Moat tags (CSV)** (254 KB) - Lista de etiquetas