PYTHON  := $(shell command -v python3.11 2>/dev/null || command -v python3 2>/dev/null || echo python)
PIP     := $(PYTHON) -m pip
VERSION := $(shell cat VERSION)

.PHONY: all install build check test clean set-version \
       install-python install-typescript install-java install-mcp \
       build-python build-typescript build-java \
       check-python check-typescript check-java check-mcp \
       ticker-python ticker-typescript ticker-java \
       mcp-server

# ---------------------------------------------------------------------------
# Aggregate targets
# ---------------------------------------------------------------------------

all: install build check

install: install-python install-typescript install-java install-mcp

build: build-python build-typescript build-java

check: check-python check-typescript check-java check-mcp

test: check

clean:
	rm -rf sdks/typescript/dist sdks/typescript/node_modules
	rm -rf sdks/java/fm-sdk/target sdks/java/examples/ticker/target
	rm -rf mcp-server/.venv
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name '*.egg-info' -exec rm -rf {} + 2>/dev/null || true

# ---------------------------------------------------------------------------
# Python SDK
# ---------------------------------------------------------------------------

install-python:
	$(PIP) install --upgrade pip
	$(PIP) install -e sdks/python

build-python:
	$(PYTHON) -m py_compile sdks/python/fm/client.py
	$(PYTHON) -m py_compile sdks/python/fm/events.py
	$(PYTHON) -m py_compile sdks/python/fm/types.py

check-python:
	$(PYTHON) -c "import fm; print('python sdk ok')"

ticker-python:
	$(PYTHON) sdks/python/ticker.py $(ARGS)

# ---------------------------------------------------------------------------
# TypeScript SDK
# ---------------------------------------------------------------------------

install-typescript:
	cd sdks/typescript && npm install

build-typescript:
	cd sdks/typescript && npx tsc

check-typescript:
	cd sdks/typescript && npx tsc --noEmit

ticker-typescript:
	cd sdks/typescript && npx tsx src/ticker.ts $(ARGS)

# ---------------------------------------------------------------------------
# Java SDK
# ---------------------------------------------------------------------------

install-java:
	cd sdks/java && mvn install -q

build-java:
	cd sdks/java && mvn package -q

check-java:
	cd sdks/java && mvn compile -q

ticker-java:
	java --enable-preview -jar sdks/java/examples/ticker/target/fm-ticker-$(VERSION).jar $(ARGS)

# ---------------------------------------------------------------------------
# MCP server
# ---------------------------------------------------------------------------

install-mcp:
	cd mcp-server && $(PYTHON) -m venv .venv && .venv/bin/pip install --upgrade pip && .venv/bin/pip install -q -e ../sdks/python "mcp[cli]"

check-mcp:
	cd mcp-server && .venv/bin/python -c "import server; print('mcp server ok')"

mcp-server:
	cd mcp-server && .venv/bin/python server.py

# ---------------------------------------------------------------------------
# Version management
# ---------------------------------------------------------------------------

set-version:
ifndef V
	$(error Usage: make set-version V=x.y.z)
endif
	@echo "$(V)" > VERSION
	@# TypeScript
	cd sdks/typescript && npm version "$(V)" --no-git-tag-version --allow-same-version
	@# Java (parent + children inherit)
	sed -i 's|<version>[^<]*</version><!-- fm-version -->|<version>$(V)</version><!-- fm-version -->|g' \
		sdks/java/pom.xml sdks/java/fm-sdk/pom.xml sdks/java/examples/ticker/pom.xml
	@# MCP server
	sed -i 's|^version = ".*"|version = "$(V)"|' mcp-server/pyproject.toml
	@echo "Version set to $(V)"
