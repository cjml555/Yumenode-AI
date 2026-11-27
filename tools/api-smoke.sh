#!/usr/bin/env bash
# Yumenode AI · (c) 2026 visitor257 · MIT License
#
# Smoke test del servidor OpenAI-compatible de la app, pensado para correr
# contra el telefono por WiFi (o por `adb forward`).
#
# Uso:
#   tools/api-smoke.sh                      # usa http://192.168.0.143:8080
#   tools/api-smoke.sh http://localhost:8080
#   EMBEDDINGS=1 tools/api-smoke.sh …       # ademas prueba /v1/embeddings
#
# Requisitos: un modelo de chat cargado y el servicio API iniciado en la app.
# Sale con codigo 0 solo si todas las comprobaciones pasan.

set -uo pipefail

BASE="${1:-http://192.168.0.143:8080}"
PASS=0
FAIL=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ok()   { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s — %s\n' "$1" "${2:-}"; FAIL=$((FAIL+1)); }
head1(){ printf '\n\033[1m%s\033[0m\n' "$1"; }

# python3 se usa para parsear JSON; falla pronto si no esta.
command -v python3 >/dev/null || { echo "Falta python3"; exit 2; }
command -v curl    >/dev/null || { echo "Falta curl"; exit 2; }

py() { python3 -c "$@"; }

head1 "Base: $BASE"

# ---------------------------------------------------------------- /health
CODE=$(curl -s -o "$TMP/health.json" -w '%{http_code}' -m 10 "$BASE/health")
if [ "$CODE" = "200" ] && py "import json,sys; d=json.load(open('$TMP/health.json')); sys.exit(0 if d.get('status')=='ok' else 1)"; then
  MODEL=$(py "import json; print(json.load(open('$TMP/health.json')).get('model',''))")
  ok "/health (modelo: $MODEL)"
else
  bad "/health" "http=$CODE"
fi

# ---------------------------------------------------------------- /v1/models
CODE=$(curl -s -o "$TMP/models.json" -w '%{http_code}' -m 10 "$BASE/v1/models")
if [ "$CODE" = "200" ] && py "import json,sys; d=json.load(open('$TMP/models.json')); sys.exit(0 if d.get('data') else 1)"; then
  MID=$(py "import json; print(json.load(open('$TMP/models.json'))['data'][0]['id'])")
  ok "/v1/models (id: $MID)"
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE/v1/models/$MID")
  [ "$CODE" = "200" ] && ok "/v1/models/{id}" || bad "/v1/models/{id}" "http=$CODE"
else
  bad "/v1/models" "http=$CODE"
fi

# ---------------------------------------------------------------- chat simple
cat > "$TMP/chat.json" <<'JSON'
{"model":"m","messages":[{"role":"user","content":"di hola en una palabra"}],"max_tokens":16}
JSON
CODE=$(curl -s -o "$TMP/chat.out" -w '%{http_code}' -m 120 \
  -X POST "$BASE/v1/chat/completions" -H 'Content-Type: application/json' -d @"$TMP/chat.json")
if [ "$CODE" = "200" ] && py "import json,sys; d=json.load(open('$TMP/chat.out')); sys.exit(0 if d['choices'][0]['message']['content'].strip() else 1)"; then
  ok "/v1/chat/completions (no-stream)"
else
  bad "/v1/chat/completions (no-stream)" "http=$CODE"
fi

# ---------------------------------------------------------------- finish_reason
cat > "$TMP/trunc.json" <<'JSON'
{"model":"m","messages":[{"role":"user","content":"cuenta hasta cien"}],"max_tokens":6}
JSON
curl -s -o "$TMP/trunc.out" -m 120 -X POST "$BASE/v1/chat/completions" \
  -H 'Content-Type: application/json' -d @"$TMP/trunc.json"
FR=$(py "import json;print(json.load(open('$TMP/trunc.out'))['choices'][0].get('finish_reason',''))" 2>/dev/null)
[ "$FR" = "length" ] && ok "finish_reason=length al agotar max_tokens" \
                     || bad "finish_reason=length" "obtenido '$FR'"

# ---------------------------------------------------------------- stream
CODE=$(curl -s -N -o "$TMP/stream.out" -w '%{http_code}' -m 120 -X POST "$BASE/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"model":"m","messages":[{"role":"user","content":"di hola"}],"max_tokens":16,"stream":true}')
if [ "$CODE" = "200" ] && grep -q 'data: \[DONE\]' "$TMP/stream.out" && [ "$(grep -c '^data: {' "$TMP/stream.out")" -ge 2 ]; then
  ok "stream SSE"
else
  bad "stream SSE" "http=$CODE"
fi

# ---------------------------------------------------------------- stream + usage
curl -s -N -o "$TMP/usage.out" -m 120 -X POST "$BASE/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"model":"m","messages":[{"role":"user","content":"di hola"}],"max_tokens":16,"stream":true,"stream_options":{"include_usage":true}}'
if py "
import json,sys
chunks=[json.loads(l[6:]) for l in open('$TMP/usage.out') if l.startswith('data: {')]
u=[c for c in chunks if 'usage' in c]
sys.exit(0 if u and u[-1]['choices']==[] and u[-1]['usage']['total_tokens']>0 else 1)
"; then
  ok "stream_options.include_usage"
else
  bad "stream_options.include_usage" "sin chunk de usage"
fi

# ---------------------------------------------------------------- tool calling
cat > "$TMP/tools.json" <<'JSON'
{"model":"m","messages":[{"role":"user","content":"¿qué tiempo hace en Toluca?"}],
 "tools":[{"type":"function","function":{"name":"get_weather","description":"Clima de una ciudad",
   "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}],
 "tool_choice":"required","max_tokens":64}
JSON
curl -s -o "$TMP/tools.out" -m 120 -X POST "$BASE/v1/chat/completions" \
  -H 'Content-Type: application/json' -d @"$TMP/tools.json"
if py "
import json,sys
d=json.load(open('$TMP/tools.out'))['choices'][0]
sys.exit(0 if d.get('finish_reason')=='tool_calls' and d['message'].get('tool_calls') else 1)
"; then
  ok "tool calling (tool_choice=required)"
else
  bad "tool calling" "sin tool_calls"
fi

# ---------------------------------------------------------------- response_format
cat > "$TMP/schema.json" <<'JSON'
{"model":"m","messages":[{"role":"user","content":"escribe un poema sobre el mar"}],
 "response_format":{"type":"json_schema","json_schema":{"name":"s","schema":{
   "type":"object","properties":{"status":{"type":"string","enum":["ok"]}},"required":["status"]}}},
 "max_tokens":64}
JSON
curl -s -o "$TMP/schema.out" -m 120 -X POST "$BASE/v1/chat/completions" \
  -H 'Content-Type: application/json' -d @"$TMP/schema.json"
if py "
import json,sys
c=json.load(open('$TMP/schema.out'))['choices'][0]['message']['content']
try: d=json.loads(c)
except Exception: sys.exit(1)
sys.exit(0 if d.get('status')=='ok' else 1)
"; then
  ok "response_format json_schema"
else
  bad "response_format json_schema" "no devolvio el JSON del schema"
fi

# ---------------------------------------------------------------- determinismo
cat > "$TMP/seed.json" <<'JSON'
{"model":"m","messages":[{"role":"user","content":"nombra dos colores"}],"temperature":1.1,"seed":7,"max_tokens":20}
JSON
for i in 1 2; do
  curl -s -m 120 -X POST "$BASE/v1/chat/completions" -H 'Content-Type: application/json' \
    -d @"$TMP/seed.json" -o "$TMP/seed$i.out"
done
if py "
import json,sys
a=[json.load(open('$TMP/seed%d.out'%i))['choices'][0]['message']['content'] for (i) in (1,2)]
sys.exit(0 if a[0]==a[1] and a[0].strip() else 1)
"; then
  ok "seed reproducible"
else
  bad "seed reproducible" "dos llamadas con seed=7 difieren"
fi

# ---------------------------------------------------------------- /v1/completions
CODE=$(curl -s -o "$TMP/comp.out" -w '%{http_code}' -m 120 -X POST "$BASE/v1/completions" \
  -H 'Content-Type: application/json' -d '{"model":"m","prompt":"La capital de Mexico es","max_tokens":12}')
if [ "$CODE" = "200" ] && py "import json,sys; d=json.load(open('$TMP/comp.out')); sys.exit(0 if d['choices'][0]['text'].strip() else 1)"; then
  ok "/v1/completions (legacy)"
else
  bad "/v1/completions (legacy)" "http=$CODE"
fi

# ---------------------------------------------------------------- 429
cat > "$TMP/long.json" <<'JSON'
{"model":"m","messages":[{"role":"user","content":"escribe un texto largo sobre grafos"}],"max_tokens":300}
JSON
curl -s -m 120 -X POST "$BASE/v1/chat/completions" -H 'Content-Type: application/json' \
  -d @"$TMP/long.json" -o /dev/null &
sleep 1
RA=$(curl -s -D - -o /dev/null -m 30 -X POST "$BASE/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"model":"m","messages":[{"role":"user","content":"hola"}],"max_tokens":4}' \
  | tr -d '\r' | awk 'tolower($0) ~ /^retry-after:/ {print $2}')
[ -n "$RA" ] && ok "429 con Retry-After ($RA)" || bad "429 con Retry-After" "sin cabecera"
wait

# ---------------------------------------------------------------- embeddings (opcional)
if [ "${EMBEDDINGS:-0}" = "1" ]; then
  if py "
import json,sys
d=json.load(open('$TMP/health.json'))
sys.exit(0 if d.get('role')=='chat' else 1)
"; then :; fi
  curl -s -o "$TMP/emb.out" -m 120 -X POST "$BASE/v1/embeddings" \
    -H 'Content-Type: application/json' -d '{"input":["hola","mundo"]}'
  if py "
import json,sys
try: d=json.load(open('$TMP/emb.out'))
except Exception: sys.exit(1)
sys.exit(0 if len(d.get('data',[]))==2 and d['data'][0].get('embedding') else 1)
"; then
    ok "/v1/embeddings (2 vectores)"
  else
    bad "/v1/embeddings" "requiere cargar el modelo en Modo embeddings (409 si no)"
  fi
fi

# ---------------------------------------------------------------- resumen
printf '\n\033[1m%d pass · %d fail\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
