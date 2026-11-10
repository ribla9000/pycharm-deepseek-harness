# DSH Assistant — a Cursor-style PyCharm plugin powered by DeepSeek Harness.
#
# The repository ships a prebuilt plugin ZIP in dist/, so installing needs no JDK:
#
#   make install        install dist/dsh-assistant-<version>.zip into PyCharm
#   make build          rebuild dist/ with Gradle (needs JDK 17)
#   make run            start a sandbox PyCharm with the plugin
#
# Run "make" or "make help" for the full target list.

SHELL := /bin/bash

PLUGIN_ID    := dsh-assistant
VERSION      := 2.1.1
ZIP_NAME     := $(PLUGIN_ID)-$(VERSION).zip
DIST_ZIP     := dist/$(ZIP_NAME)
BUILD_ZIP    := build/distributions/$(ZIP_NAME)
GRADLEW      := ./gradlew
PLUGINS_DIR ?=

.DEFAULT_GOAL := help

.PHONY: help install uninstall build rebuild run verify clean

help:
	@printf '\nDSH Assistant %s — PyCharm plugin powered by DeepSeek Harness\n\n' '$(VERSION)'
	@printf 'Targets:\n\n'
	@printf '  make install     Install the prebuilt plugin from dist/ (no JDK needed)\n'
	@printf '  make uninstall   Remove the installed plugin\n'
	@printf '  make build       Rebuild dist/%s with Gradle (needs JDK 17)\n' '$(ZIP_NAME)'
	@printf '  make rebuild     make build, then install the fresh ZIP\n'
	@printf '  make run         Start a sandbox PyCharm with the plugin\n'
	@printf '  make verify      Run the JetBrains Plugin Verifier\n'
	@printf '  make clean       Remove Gradle build output (dist/ is kept)\n'
	@printf '\nVariables:\n\n'
	@printf '  PLUGINS_DIR=...  PyCharm plugins directory; detected automatically when empty\n'
	@printf '\nExamples:\n\n'
	@printf '  make install\n'
	@printf '  make install PLUGINS_DIR="$$HOME/.local/share/JetBrains/PyCharm2025.1/plugins"\n'
	@printf '  make rebuild\n\n'

install:
	@set -euo pipefail; \
	zip="$(DIST_ZIP)"; \
	if [ ! -f "$$zip" ]; then \
		echo "error: $$zip not found."; \
		echo "Run 'make build' to create it (JDK 17 required)."; \
		exit 1; \
	fi; \
	plugins=$$(bash "$(CURDIR)/scripts/plugins-dir.sh"); \
	target="$$plugins/$(PLUGIN_ID)"; \
	case "$$target" in \
		*/$(PLUGIN_ID)) ;; \
		*) echo "error: refusing to touch '$$target'"; exit 1 ;; \
	esac; \
	mkdir -p "$$plugins"; \
	rm -rf "$$target"; \
	unzip -q "$$zip" -d "$$plugins"; \
	if ! ls "$$target"/lib/*.jar >/dev/null 2>&1; then \
		echo "error: $$zip does not look like a plugin archive (no lib/*.jar)."; \
		exit 1; \
	fi; \
	echo "Installed $(ZIP_NAME)"; \
	echo "  -> $$target"; \
	echo "Restart PyCharm to load DSH Assistant."

uninstall:
	@set -euo pipefail; \
	plugins=$$(bash "$(CURDIR)/scripts/plugins-dir.sh"); \
	target="$$plugins/$(PLUGIN_ID)"; \
	case "$$target" in \
		*/$(PLUGIN_ID)) ;; \
		*) echo "error: refusing to touch '$$target'"; exit 1; \
	esac; \
	if [ -d "$$target" ]; then \
		rm -rf "$$target"; \
		echo "Removed $$target"; \
	else \
		echo "Nothing to remove: $$target does not exist."; \
	fi

build:
	@printf 'Building %s with Gradle (JDK 17 required)...\n' '$(ZIP_NAME)'
	$(GRADLEW) buildPlugin --console=plain
	@mkdir -p dist
	@cp -f "$(BUILD_ZIP)" "$(DIST_ZIP)"
	@printf 'Built %s\n' '$(DIST_ZIP)'

rebuild: build install

run:
	$(GRADLEW) runIde --console=plain

verify:
	$(GRADLEW) verifyPlugin --console=plain

clean:
	$(GRADLEW) clean
