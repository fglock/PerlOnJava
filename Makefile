.PHONY: all clean test test-unit test-interpreter test-bundled-modules test-exiftool test-all test-gradle test-gradle-unit test-gradle-all test-gradle-parallel test-maven-parallel build run wrapper check-java-gradle dev ci sbom sbom-java sbom-perl sbom-clean check-links

all: build

# CI build - optimized for CI/CD environments
ci: check-java-gradle
ifeq ($(OS),Windows_NT)
	mvn clean test -B
else
	./gradlew build --no-daemon --stacktrace
endif

# Check Java/Gradle compatibility and fix if needed
# For Java 25+, we need Gradle 9.1.0+ (see https://docs.gradle.org/current/userguide/compatibility.html)
# Note: On Windows CI, Make uses Git Bash, so we use bash-compatible syntax throughout
# Note: We modify gradle-wrapper.properties directly because older gradle can't run on Java 25+
check-java-gradle:
	@JAVA_MAJOR=$$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/'); \
	if [ "$$JAVA_MAJOR" -ge 25 ] 2>/dev/null; then \
		echo "Java $$JAVA_MAJOR detected - ensuring Gradle 9.1+ compatibility..."; \
		rm -rf ~/.gradle/wrapper/dists/gradle-8.* ~/.gradle/wrapper/dists/gradle-9.0* 2>/dev/null || true; \
		GRADLE_MAJOR=$$(grep distributionUrl gradle/wrapper/gradle-wrapper.properties 2>/dev/null | sed -E 's/.*gradle-([0-9]+)\..*/\1/'); \
		GRADLE_MINOR=$$(grep distributionUrl gradle/wrapper/gradle-wrapper.properties 2>/dev/null | sed -E 's/.*gradle-[0-9]+\.([0-9]+).*/\1/'); \
		if [ "$$GRADLE_MAJOR" -lt 9 ] 2>/dev/null || ([ "$$GRADLE_MAJOR" -eq 9 ] 2>/dev/null && [ "$$GRADLE_MINOR" -lt 1 ] 2>/dev/null); then \
			echo "Updating gradle-wrapper.properties to use Gradle 9.1.0 (current: $$GRADLE_MAJOR.$$GRADLE_MINOR)..."; \
			sed -i.bak 's|gradle-[0-9][0-9]*\.[0-9][0-9]*[^/]*-bin\.zip|gradle-9.1.0-bin.zip|' gradle/wrapper/gradle-wrapper.properties && rm -f gradle/wrapper/gradle-wrapper.properties.bak; \
		fi; \
	elif [ ! -f ./gradlew ]; then \
		gradle wrapper || true; \
	fi

wrapper: check-java-gradle

# Standard build - incremental compilation with parallel tests (4 JVMs)
build: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat classes testUnitParallel --parallel shadowJar
else
	./gradlew classes testUnitParallel --parallel shadowJar
endif

# `make dev` used to build without tests — removed because it allowed broken
# commits to land silently. Always use `make`, which builds + runs unit tests.
dev:
	@echo "Error: 'make dev' has been removed. Use 'make' — it must pass before commits/pushes."
	@echo "See AGENTS.md: \"Always run \`make\` and ensure it passes before pushing commits\"."
	@exit 1

# Default test target - fast unit tests using perl_test_runner.pl
test: test-unit

# Fast unit tests only (from src/test/resources/unit/ directory)
# Uses Gradle's testUnitParallel (same as default make build)
test-unit: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat testUnitParallel --parallel
else
	./gradlew testUnitParallel --parallel
endif

# Unit tests using bytecode interpreter backend (feature parity check)
test-interpreter:
	@echo "Running unit tests with bytecode interpreter..."
	JPERL_INTERPRETER=1 perl dev/tools/perl_test_runner.pl --jobs 8 --timeout 60 --output test_interpreter_results.json src/test/resources/unit

# Bundled CPAN module tests (XML::Parser, etc.)
# Tests live under src/test/resources/module/{ModuleName}/t/
test-bundled-modules: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat testModule --rerun-tasks
else
	./gradlew testModule --rerun-tasks
endif

# Image::ExifTool test suite (Image-ExifTool-13.44/t/ directory)
test-exiftool:
	@echo "Running Image::ExifTool tests..."
	@if [ -d Image-ExifTool-13.44/t ]; then \
		perl dev/tools/run_exiftool_tests.pl --output test_exiftool_results.json; \
	else \
		echo "Error: Image-ExifTool-13.44/ directory not found."; \
		exit 1; \
	fi

# Perl 5 core test suite (perl5_t/t/ directory)
# Run 'perl dev/import-perl5/sync.pl' first to populate perl5_t/
test-perl5:
	@echo "Running Perl 5 core test suite..."
	@if [ -d perl5_t/t ]; then \
		perl dev/tools/perl_test_runner.pl --jobs 8 --timeout 60 --output test_results.json perl5_t/t; \
	else \
		echo "Error: perl5_t/t/ directory not found. Run 'perl dev/import-perl5/sync.pl' first."; \
		exit 1; \
	fi

# Perl 5 module tests (auto-discovers all subdirectories in perl5_t/ except t/)
# Run 'perl dev/import-perl5/sync.pl' first to populate perl5_t/
test-modules:
	@echo "Running Perl 5 module tests..."
	@if [ -d perl5_t ]; then \
		MODULE_DIRS=$$(find perl5_t -maxdepth 1 -type d ! -name perl5_t ! -name t -name '[A-Z]*' 2>/dev/null | sort); \
		if [ -n "$$MODULE_DIRS" ]; then \
			echo "Found module test directories: $$MODULE_DIRS"; \
			perl dev/tools/perl_test_runner.pl --jobs 8 --timeout 60 --output test_modules_results.json $$MODULE_DIRS; \
		else \
			echo "Warning: No module test directories found in perl5_t/. Run 'perl dev/import-perl5/sync.pl' first."; \
		fi \
	else \
		echo "Error: perl5_t/ directory not found. Run 'perl dev/import-perl5/sync.pl' first."; \
		exit 1; \
	fi

# Comprehensive tests - runs both Perl 5 core tests and module tests
test-all: test-perl5 test-modules

# Alternative: Run tests using JUnit/Gradle (for CI/CD integration)
# Uses parallel execution by default (4 JVMs)
test-gradle: test-gradle-parallel

# Fast unit tests via Gradle/JUnit
test-gradle-unit: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat testUnit --rerun-tasks
else
	./gradlew testUnit --rerun-tasks
endif

# All tests via Gradle/JUnit
test-gradle-all: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat testAll --rerun-tasks
else
	./gradlew testAll --rerun-tasks
endif

# Parallel unit tests via Gradle/JUnit (4 JVMs)
test-gradle-parallel: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat testUnitParallel --parallel --rerun-tasks
else
	./gradlew testUnitParallel --parallel --rerun-tasks
endif

# Parallel unit tests via Maven (4 JVMs)
test-maven-parallel:
ifeq ($(OS),Windows_NT)
	start /B mvn test -Pshard1 & start /B mvn test -Pshard2 & start /B mvn test -Pshard3 & start /B mvn test -Pshard4
else
	mvn test -Pshard1 & mvn test -Pshard2 & mvn test -Pshard3 & mvn test -Pshard4 & wait
endif

clean: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat clean
else
	./gradlew clean
endif

deb: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat buildDeb
else
	./gradlew buildDeb
endif

# SBOM (Software Bill of Materials) generation
# See dev/design/sbom.md for details

# Generate combined SBOM (Java dependencies + Perl modules merged)
sbom: sbom-java sbom-perl
	@echo "Merging SBOMs..."
	perl dev/tools/merge-sbom.pl build/reports/bom.json build/reports/perl-bom.json > build/reports/sbom.json
	@echo "Combined SBOM generated: build/reports/sbom.json"

# Generate Java SBOM using CycloneDX Gradle plugin
sbom-java: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat cyclonedxBom
else
	./gradlew cyclonedxBom
endif

# Generate Perl modules SBOM
sbom-perl:
	@mkdir -p build/reports
	perl dev/tools/generate-perl-sbom.pl > build/reports/perl-bom.json
	@echo "Perl SBOM generated: build/reports/perl-bom.json"

# Clean generated SBOMs
sbom-clean:
	rm -f build/reports/bom.json build/reports/bom.xml build/reports/perl-bom.json build/reports/sbom.json

# Documentation link checker
# Requires: brew install lychee (or cargo install lychee)
check-links:
	@command -v lychee >/dev/null 2>&1 || { echo "Error: lychee not found. Install with: brew install lychee"; exit 1; }
	@echo "Checking documentation links..."
	lychee --offline *.md docs/ dev/design/ dev/architecture/

