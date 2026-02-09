#!/bin/bash
set -e

echo "Installing external parsers on macOS..."

# 1. Install Homebrew if missing
if ! command -v brew >/dev/null 2>&1; then
  echo "Homebrew not found. Install it first:"
  echo "https://brew.sh"
  exit 1
fi

# 2. Node.js (for JS / TS parsing)
brew install node

# 3. Babel parser (JS / TS)
npm install -g @babel/parser @babel/core

# 4. Python
brew install python

# 5. Go (correct macOS binary)
brew install go

# 6. Ruby
brew install ruby

# 7. Parser scripts directory (NO sudo)
PARSER_DIR="$HOME/pr-analysis/parsers"
mkdir -p "$PARSER_DIR"

echo "External parsers installed successfully!"
echo "Parser directory: $PARSER_DIR"
