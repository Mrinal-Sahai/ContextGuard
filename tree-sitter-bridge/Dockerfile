# Install Go for go-types-bridge compilation (or copy pre-built binary)
RUN apt-get install -y golang-go

# Build the Go bridge
COPY tree-sitter-bridge/ /app/tree-sitter-bridge/
RUN cd /app/tree-sitter-bridge && npm install && npm run build:go

# Pyright is installed as part of npm install (optional dep)
# If you want to guarantee it: npm install pyright