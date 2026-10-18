#!/bin/sh
# Downloads a few public sample .riv files (rive-app/rive-react examples) into rive-library/ and writes a test manifest. TEST ONLY: check licences before publishing.
set -e
cd "$(dirname "$0")/.."
mkdir -p rive-library
curl -fsSL https://codeload.github.com/rive-app/rive-react/tar.gz/HEAD -o /tmp/rive-react.tgz
tar xzf /tmp/rive-react.tgz -C /tmp --wildcards '*/examples/public/avatars.riv' '*/examples/public/rating.riv'
cp /tmp/rive-react-*/examples/public/avatars.riv /tmp/rive-react-*/examples/public/rating.riv rive-library/
cat > rive-library/manifest.json <<'JSON'
{ "assets": [
  { "file": "avatars.riv", "title": "Avatar character (sample)", "keywords": ["character", "avatar", "mascot", "person", "user"] },
  { "file": "rating.riv",  "title": "Star rating (sample)",      "keywords": ["rating", "review", "stars", "feedback"] }
] }
JSON
echo "Sample Rive files installed in rive-library/ (test only)."
