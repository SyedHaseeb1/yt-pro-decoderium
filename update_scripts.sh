#!/usr/bin/env fish

echo "Downloading JS scripts from cdn.jsdelivr.net..."

set ASSETS_DIR "app/src/main/assets/scripts"

# Create directory if it doesn't exist
mkdir -p $ASSETS_DIR

# Download ytpro index script
curl -s -L "https://cdn.jsdelivr.net/npm/ytpro" -o "$ASSETS_DIR/ytpro.js"

# Download bgplay.js
curl -s -L "https://cdn.jsdelivr.net/npm/ytpro/bgplay.js" -o "$ASSETS_DIR/bgplay.js"

# Download innertube.js
curl -s -L "https://cdn.jsdelivr.net/npm/ytpro/innertube.js" -o "$ASSETS_DIR/innertube.js"

echo "Download complete! Scripts are saved to $ASSETS_DIR"
