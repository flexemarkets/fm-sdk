PYTHON  := $(shell command -v python3.11 2>/dev/null || command -v python3 2>/dev/null || echo python)
# Python work happens in a venv — system interpreters are increasingly
# externally-managed (PEP 668), so `pip install` into them is refused.
PY_VENV := sdks/python/.venv
VENV_PY := $(PY_VENV)/bin/python
VERSION := $(shell cat VERSION)

.PHONY: all install build check test clean set-version set-spi-version spi-version \
       install-python install-typescript install-java install-mcp \
       build-python build-typescript build-java \
       check-parity check-python check-typescript check-java check-mcp \
       ticker-python ticker-typescript ticker-java \
       mcp-server \
       publish publish-python publish-typescript publish-java

# ---------------------------------------------------------------------------
# Aggregate targets
# ---------------------------------------------------------------------------

all: install build check

install: install-python install-typescript install-java install-mcp

build: build-python build-typescript build-java

check: check-parity check-python check-typescript check-java check-mcp

# The three SDKs are hand-written and every type is declared three times, so
# nothing but this holds them to the same wire format. Runs first because it
# needs no toolchain — a mismatch should be reported before spending time
# installing three of them.
check-parity:
	$(PYTHON) scripts/check-parity.py

test: check

clean:
	rm -rf sdks/typescript/dist sdks/typescript/node_modules
	rm -rf sdks/java/fm-sdk/target sdks/java/examples/ticker/target
	rm -rf sdks/python/dist sdks/python/*.egg-info sdks/python/.venv
	rm -rf mcp-server/.venv
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name '*.egg-info' -exec rm -rf {} + 2>/dev/null || true

# ---------------------------------------------------------------------------
# Python SDK
# ---------------------------------------------------------------------------

install-python:
	$(PYTHON) -m venv $(PY_VENV)
	$(VENV_PY) -m pip install --upgrade pip
	$(VENV_PY) -m pip install -e sdks/python

build-python:
	$(VENV_PY) -m py_compile sdks/python/fm/client.py
	$(VENV_PY) -m py_compile sdks/python/fm/events.py
	$(VENV_PY) -m py_compile sdks/python/fm/types.py

check-python:
	$(VENV_PY) -c "import fm; print('python sdk ok')"

ticker-python:
	$(VENV_PY) sdks/python/ticker.py $(ARGS)

publish-python:
	$(VENV_PY) -m pip install build twine
	$(VENV_PY) -m build sdks/python
	$(VENV_PY) -m twine upload sdks/python/dist/*

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

publish-typescript:
	cd sdks/typescript && npm publish --access public

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

publish-java:
	cd sdks/java && mvn deploy -P release

# ---------------------------------------------------------------------------
# MCP server
# ---------------------------------------------------------------------------

# mcp is pinned below 2. The dependency was unpinned, so a fresh venv picked
# up 2.0.0, which renamed FastMCP to MCPServer under mcp.server.mcpserver and
# broke server.py's import -- the failure looked like a missing venv rather
# than an API change, which is what an unpinned major buys you.
#
# Porting server.py to the 2.x API is real work and unrelated to the SDK
# itself: mcp-server is a local tool and is in no publish target.
install-mcp:
	cd mcp-server && $(PYTHON) -m venv .venv && .venv/bin/pip install --upgrade pip && .venv/bin/pip install -q -e ../sdks/python "mcp[cli]<2"

check-mcp:
	cd mcp-server && .venv/bin/python -c "import server; print('mcp server ok')"

mcp-server:
	cd mcp-server && .venv/bin/python server.py

# ---------------------------------------------------------------------------
# Publishing (all SDKs)
# ---------------------------------------------------------------------------

publish: publish-python publish-typescript publish-java

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
	@# Java (parent + children inherit). fm-spi's own <version> is not touched
	@# here -- it is a contract on its own line, see set-spi-version. Its parent
	@# reference is, so it keeps building against this parent.
	sed -i 's|<version>[^<]*</version><!-- fm-version -->|<version>$(V)</version><!-- fm-version -->|g' \
		sdks/java/pom.xml sdks/java/fm-sdk/pom.xml sdks/java/fm-spi/pom.xml sdks/java/examples/ticker/pom.xml
	@# MCP server
	sed -i 's|^version = ".*"|version = "$(V)"|' mcp-server/pyproject.toml
	@echo "Version set to $(V)"
	@echo "fm-spi stays at $$(make -s spi-version) — use set-spi-version to move it"

# The SPI is a contract, not a client: two interfaces and a version constant,
# changing rarely and mattering to every host and provider when it does. It is
# versioned apart from the SDK so that a busy client release does not mint an
# empty contract release for consumers to review.
#
# Bump the patch for an additive change. Bump the major only together with
# ServiceProvider.SPI_VERSION, because a host rejects a provider whose major
# differs from its own -- that is the whole point of the constant.
set-spi-version:
ifndef V
	$(error Usage: make set-spi-version V=x.y.z)
endif
	sed -i 's|<version>[^<]*</version><!-- spi-version -->|<version>$(V)</version><!-- spi-version -->|' \
		sdks/java/fm-spi/pom.xml
	@echo "fm-spi version set to $(V) (SDK line unchanged at $$(cat VERSION))"
	@echo "Remember ServiceProvider.SPI_VERSION if the major moved."

spi-version:
	@grep -o '<version>[^<]*</version><!-- spi-version -->' sdks/java/fm-spi/pom.xml \
		| sed 's|<version>\(.*\)</version>.*|\1|'
