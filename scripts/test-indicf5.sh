#!/usr/bin/env bash
# Quick IndicF5 end-to-end test: tts-indic directly, through the tts proxy, through the backend.
# Writes /tmp/indic-*.wav - copy them to your PC and listen (clear Kannada voice = OK, buzz/noise = vocoder problem).
set -u
TEXT='ನಮಸ್ಕಾರ! ಇದು ನನ್ನ ಧ್ವನಿಯ ಮಾದರಿ. ಕಥೆ ಕೇಳಲು ಸಿದ್ಧರಾಗಿ.'
VOICE='indic:kn-in-sapna'
echo "== health";  curl -s localhost:5003/health | head -c 300; echo
run() { # name url body
  local code
  code=$(curl -s -o "/tmp/indic-$1.wav" -w '%{http_code}' -X POST "$2" -H 'Content-Type: application/json' -d "$3")
  echo "== $1: HTTP $code"
  if [ "$code" = "200" ]; then
    docker compose exec -T tts ffprobe -v error -show_entries format=duration -of csv=p=0 - < "/tmp/indic-$1.wav" 2>/dev/null \
      | sed 's/^/   duration s: /' || true
    ls -la "/tmp/indic-$1.wav"
  else
    head -c 600 "/tmp/indic-$1.wav"; echo
  fi
}
run direct  http://localhost:5003/api/tts          "{\"text\":\"$TEXT\",\"voice\":\"$VOICE\"}"
run proxy   http://localhost:5002/api/tts          "{\"text\":\"$TEXT\",\"voice\":\"$VOICE\",\"speed\":1.0,\"pitch\":1.0,\"language\":\"Kannada\"}"
run backend http://localhost:8080/api/tts/preview  "{\"text\":\"$TEXT\",\"voice\":\"$VOICE\",\"speed\":1.0,\"pitch\":1.0}"
echo "== vocoder fix in log:"; docker compose logs tts-indic 2>/dev/null | grep -i "vocoder" | tail -3
