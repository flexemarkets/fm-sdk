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
       test-python test-typescript test-java \
       ticker-python ticker-typescript ticker-java \
       mcp-server \
       publish publish-python publish-typescript publish-java \
       check-publish check-publish-python check-publish-typescript check-publish-java \
       publish-spi check-publish-spi

# ---------------------------------------------------------------------------
# Aggregate targets
# ---------------------------------------------------------------------------

all: install build check

install: install-python install-typescript install-java install-mcp

build: build-python build-typescript build-java

check: check-parity check-python check-typescript check-java check-mcp \
       test-python test-typescript test-java

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
	$(VENV_PY) -m pip install -e "sdks/python[test]"

build-python:
	$(VENV_PY) -m py_compile sdks/python/fm/client.py
	$(VENV_PY) -m py_compile sdks/python/fm/events.py
	$(VENV_PY) -m py_compile sdks/python/fm/types.py

check-python:
	$(VENV_PY) -c "import fm; print('python sdk ok')"

test-python:
	cd sdks/python && ../../$(VENV_PY) -m pytest -q

ticker-python:
	$(VENV_PY) sdks/python/ticker.py $(ARGS)

publish-python: check-publish-python
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

# tsc --noEmit is not this. tsconfig's `include` is ["src"], so the typecheck
# above never looks at test/, and tsx strips types without checking them --
# a test can assert something the source contradicts and still pass.
test-typescript:
	cd sdks/typescript && npm test

ticker-typescript:
	cd sdks/typescript && npx tsx src/ticker.ts $(ARGS)

# OTP=<code> is passed through to npm for accounts with two-factor auth on
# writes. Without it npm prompts, and a prompt inside make is a hang.
publish-typescript: check-publish-typescript
	cd sdks/typescript && npm publish --access public $(if $(OTP),--otp=$(OTP),)

# ---------------------------------------------------------------------------
# Java SDK
# ---------------------------------------------------------------------------

install-java:
	cd sdks/java && mvn install -q

build-java:
	cd sdks/java && mvn package -q

check-java:
	cd sdks/java && mvn compile -q

test-java:
	cd sdks/java && mvn -o test

ticker-java:
	java --enable-preview -jar sdks/java/examples/ticker/target/fm-ticker-$(VERSION).jar $(ARGS)

publish-java: check-publish-java
	cd sdks/java && mvn deploy -P release

# fm-spi alone, for a release where only the contract changed.
#
# -pl narrows the reactor to that one module, so central-publishing stages only
# its artifacts. Without it the deploy carries fm-sdk too, and Central refuses
# the whole bundle because that version is already there. The alternative was
# excluding fm-sdk by hand for one release and remembering to put it back.
publish-spi: check-publish-spi
	cd sdks/java && mvn deploy -P release -pl fm-spi

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

# Gated on purpose. The three uploads cannot be undone and cannot be replaced,
# so a run that succeeds on PyPI and fails on Central burns the version on one
# and leaves it unusable on the other. check-publish verifies every credential
# and every registry's view of this version before the first byte is sent.
#
# npm goes FIRST, and the order is load-bearing. It is the only registry that
# can stop and ask a human something -- a 2FA one-time password -- and when it
# ran second, 0.0.9 was already live on PyPI by the time it refused. PyPI is
# append-only, so that version was burned there and unusable everywhere else.
# Put the registry that can demand interaction where a refusal costs nothing.
publish: check-publish publish-typescript publish-python publish-java

# Every publish target carries its own gate as well, because each is also run
# on its own -- and when only the aggregate was gated, `make publish-python`
# and `make publish-typescript` put 0.0.7 on PyPI and npm with nothing checked.
# A gate that one target can walk around is not a gate.
#
# Running `make publish` therefore checks a registry twice: once up front via
# check-publish, once at its own target. That is a handful of HTTP requests,
# and it is what makes the individual targets safe in isolation.
check-publish:
	@scripts/check-publish.sh all

check-publish-python:
	@scripts/check-publish.sh pypi

check-publish-typescript:
	@scripts/check-publish.sh npm

check-publish-java:
	@scripts/check-publish.sh java

check-publish-spi:
	@scripts/check-publish.sh spi

# ---------------------------------------------------------------------------
# Version management
# ---------------------------------------------------------------------------

# MAVEN_V exists because the three ecosystems disagree about how to spell "not
# released yet", and only Maven has a word for it.
#
# PEP 440 has no SNAPSHOT: 0.0.13-SNAPSHOT is rejected outright, and hatchling
# fails the build reading VERSION. Its spelling is 0.0.13.dev0 -- which npm does
# not reject but silently rewrites to 0.0.1-3.dev0, a different version. The one
# string all three take verbatim is 0.0.13-dev0, and Maven reads that as an
# ordinary fixed version rather than a snapshot.
#
# So: V is the shared version, and MAVEN_V overrides it for the poms alone when
# the two must differ. Omit it and everything moves together as before, which is
# what a real release does -- this is for the window in between.
#
#   make set-version V=0.0.13-dev0 MAVEN_V=0.0.13-SNAPSHOT
MAVEN_V ?= $(V)

set-version:
ifndef V
	$(error Usage: make set-version V=x.y.z [MAVEN_V=x.y.z-SNAPSHOT])
endif
	@echo "$(V)" > VERSION
	@# TypeScript
	cd sdks/typescript && npm version "$(V)" --no-git-tag-version --allow-same-version
	@# Java (parent + children inherit). fm-spi's own <version> is not touched
	@# here -- it is a contract on its own line, see set-spi-version. Its parent
	@# reference is, so it keeps building against this parent.
	sed -i 's|<version>[^<]*</version><!-- fm-version -->|<version>$(MAVEN_V)</version><!-- fm-version -->|g' \
		sdks/java/pom.xml sdks/java/fm-sdk/pom.xml sdks/java/fm-spi/pom.xml sdks/java/examples/ticker/pom.xml
	@# MCP server
	sed -i 's|^version = ".*"|version = "$(V)"|' mcp-server/pyproject.toml
	@# The Java README's dependency snippet. Marked rather than matched
	@# loosely, because a bare version number appears in prose too. It said
	@# 0.0.4 while Central was on 0.0.12 -- eight releases of drift, because
	@# nothing moved it and nothing failed when it was wrong.
	sed -i 's|<version>[^<]*</version><!-- fm-readme-version -->|<version>$(V)</version><!-- fm-readme-version -->|' \
		sdks/java/README.md
	sed -i 's|fm-sdk:[^"]*") // fm-readme-version|fm-sdk:$(V)") // fm-readme-version|' \
		sdks/java/README.md
	@echo "Version set to $(V) (Maven: $(MAVEN_V))"
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
