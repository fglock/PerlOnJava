.PHONY: all clean test test-unit test-interpreter check-thread-test-sources check-thread-core-test-sources check-thread-ecosystem-test-sources check-thread-regex-test-sources test-thread-tooling test-threads test-threads-core test-threads-core-platform test-threads-core-mode test-threads-windows test-threads-regex test-threads-release test-threads-ecosystem test-bundled-modules test-cpan-distroprefs test-exiftool test-all test-gradle test-gradle-unit test-gradle-all test-gradle-parallel test-maven-parallel build run wrapper check-java-gradle dev ci sbom sbom-java sbom-perl sbom-clean check-links perl5-update perl5-sync perl5-sync-check

PERL ?= perl

THREAD_DIST_DIRS := perl5/dist/threads/t perl5/dist/threads-shared/t perl5/dist/Thread-Queue/t perl5/dist/Thread-Semaphore/t
THREAD_PLATFORM_TESTS := \
	perl5/dist/threads/t/end.t \
	perl5/dist/threads/t/exit.t \
	perl5/dist/threads/t/free.t \
	perl5/dist/threads/t/free2.t \
	perl5/dist/threads/t/join.t \
	perl5/dist/threads/t/kill.t \
	perl5/dist/threads/t/kill2.t \
	perl5/dist/threads/t/kill3.t \
	perl5/dist/threads/t/stack.t \
	perl5/dist/threads/t/stack_env.t \
	perl5/dist/threads/t/state.t \
	perl5/dist/threads/t/zz_deadlock.t \
	perl5/dist/threads-shared/t/cond.t \
	perl5/dist/threads-shared/t/wait.t \
	perl5/dist/threads-shared/t/waithires.t \
	perl5/dist/Thread-Queue/t/09_ended.t \
	perl5/dist/Thread-Queue/t/10_timed.t \
	perl5/dist/Thread-Semaphore/t/04_nonblocking.t \
	perl5/dist/Thread-Semaphore/t/06_timed.t
THREAD_REGEX_ANCHOR_TESTS := \
	perl5_t/t/re/pat_psycho_thr.t \
	perl5_t/t/re/pat_special_cc_thr.t \
	perl5_t/t/re/reg_email_thr.t \
	perl5_t/t/re/stclass_threads.t \
	perl5_t/t/re/user_prop_race_thr.t

# Complete Perl-core ithread compatibility matrix. Direct companions are run
# first so a language/regex failure cannot be misclassified as a snapshot bug.
# The four resource-sensitive regex families run serially because the upstream
# fixtures use shared temporary paths.
THREAD_CORE_DIRECT_PARALLEL := \
	perl5_t/t/op/index.t \
	perl5_t/t/op/substr.t \
	perl5_t/t/re/pat_re_eval.t \
	perl5_t/t/re/pat_rt_report.t \
	perl5_t/t/re/pat_special_cc.t \
	perl5_t/t/re/reg_email.t \
	perl5_t/t/re/regexp_unicode_prop.t \
	perl5_t/t/re/speed.t
THREAD_CORE_DIRECT_SERIAL := \
	perl5_t/t/re/pat.t \
	perl5_t/t/re/pat_advanced.t \
	perl5_t/t/re/pat_psycho.t \
	perl5_t/t/re/regexp_qr_embed.t
THREAD_CORE_NONREGEX_WRAPPERS := \
	perl5_t/t/class/threads.t \
	perl5_t/t/op/index_thr.t \
	perl5_t/t/op/substr_thr.t \
	perl5_t/t/op/threads-dirh.t \
	perl5_t/t/op/threads.t
THREAD_CORE_REGEX_WRAPPER_PARALLEL := \
	perl5_t/t/re/pat_re_eval_thr.t \
	perl5_t/t/re/pat_rt_report_thr.t \
	perl5_t/t/re/pat_special_cc_thr.t \
	perl5_t/t/re/reg_email_thr.t \
	perl5_t/t/re/regexp_unicode_prop_thr.t \
	perl5_t/t/re/speed_thr.t \
	perl5_t/t/re/stclass_threads.t \
	perl5_t/t/re/user_prop_race_thr.t
THREAD_CORE_WRAPPER_SERIAL := \
	perl5_t/t/re/pat_thr.t \
	perl5_t/t/re/pat_advanced_thr.t \
	perl5_t/t/re/pat_psycho_thr.t \
	perl5_t/t/re/regexp_qr_embed_thr.t
THREAD_CORE_TESTS := $(THREAD_CORE_DIRECT_PARALLEL) $(THREAD_CORE_DIRECT_SERIAL) $(THREAD_CORE_NONREGEX_WRAPPERS) $(THREAD_CORE_REGEX_WRAPPER_PARALLEL) $(THREAD_CORE_WRAPPER_SERIAL)
THREAD_ECOSYSTEM_UPSTREAM_TESTS := \
	perl5/dist/Storable/t/threads.t \
	perl5/cpan/Test-Simple/t/Legacy/Regression/683_thread_todo.t \
	perl5/cpan/Test-Simple/t/Legacy/is_deeply_with_threads.t \
	perl5/cpan/Test-Simple/t/Legacy/overload_threads.t \
	perl5/cpan/Test-Simple/t/Legacy/subtest/threads.t \
	perl5/cpan/Test-Simple/t/Legacy/thread_taint.t \
	perl5/cpan/Test-Simple/t/Legacy/threads.t \
	perl5/cpan/Test-Simple/t/Legacy_And_Test2/thread_init_warning.t \
	perl5/cpan/Test-Simple/t/Test2/acceptance/try_it_threads.t \
	perl5/cpan/Test-Simple/t/modules/Require/Threads.t \
	dev/test-corpora/Moose-2.4000/t/todo_tests/moose_and_threads.t

all: build

perl5-update:
	$(PERL) dev/import-perl5/update_perl5.pl

perl5-sync:
	$(PERL) dev/import-perl5/update_perl5.pl --sync $(if $(FILTER),--filter "$(FILTER)",)

perl5-sync-check:
	$(PERL) dev/import-perl5/update_perl5.pl --sync --verify-idempotent $(if $(FILTER),--filter "$(FILTER)",)

# CI build - optimized for CI/CD environments
ci: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat build --no-daemon --stacktrace
else
	./gradlew build --no-daemon --stacktrace
endif

# Check Java/Gradle compatibility and fix if needed
# For Java 25+, we need Gradle 9.1.0+ (see https://docs.gradle.org/current/userguide/compatibility.html)
# Note: On Windows CI, Make uses Git Bash, so we use bash-compatible syntax throughout
# Note: We modify gradle-wrapper.properties directly because older gradle can't run on Java 25+
check-java-gradle:
	@JAVA_MAJOR=$$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/'); \
	case "$$JAVA_MAJOR" in ''|*[!0-9]*) \
		echo "ERROR: PerlOnJava requires Java 24 or later (found: $${JAVA_MAJOR:-unavailable})."; \
		exit 1; \
	esac; \
	if [ "$$JAVA_MAJOR" -lt 24 ]; then \
		echo "ERROR: PerlOnJava requires Java 24 or later (found: $${JAVA_MAJOR:-unavailable})."; \
		exit 1; \
	fi; \
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

# Standard build - incremental compilation with parallel tests (5 JVMs; last shard isolates heavy tests)
build: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat classes testUnitParallel --parallel shadowJar
else
	./gradlew classes testUnitParallel --parallel shadowJar
endif

# Focused vendored-Joni unit gate for parser/matcher iteration. A full `make`
# remains required before pushing or updating a PR.
test-joni: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat testJoni
else
	./gradlew testJoni
endif

# `make dev` is disabled on purpose.
#
# It used to be a "build without running tests" shortcut, but that is
# precisely what makes it dangerous: it lets changes land on a branch
# without having ever been exercised by the unit test suite.  Agents
# (and humans in a hurry) reach for `make dev` to iterate faster and
# then forget to run `make` before pushing, so regressions sneak in.
#
# Use `make` (the default target) instead: it builds *and* runs the
# fast unit tests.  If you really need a no-test build for a very
# specific reason, invoke Gradle directly (`./gradlew shadowJar`) and
# own the consequences.
dev:
	@echo "ERROR: 'make dev' is disabled on purpose."
	@echo ""
	@echo "  It skipped the unit tests, which caused regressions to slip"
	@echo "  into commits.  Please use 'make' (which builds + tests) for"
	@echo "  everyday iteration."
	@echo ""
	@echo "  If you truly need a no-test build, invoke Gradle directly:"
	@echo "      ./gradlew shadowJar installDist"
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

# Verify the unchanged upstream test distributions are available. GitHub CI
# sparse-checks out the latest upstream default branch; local developers
# normally use their adjacent/gitignored perl5 source tree.
check-thread-test-sources:
	@for dir in $(THREAD_DIST_DIRS); do \
		if [ ! -d "$$dir" ]; then \
			echo "Error: $$dir is missing."; \
			echo "Run 'make perl5-update' to populate or update ./perl5 before running the thread gates."; \
			exit 1; \
		fi; \
	done

check-thread-regex-test-sources:
	@for file in $(THREAD_REGEX_ANCHOR_TESTS); do \
		if [ ! -f "$$file" ]; then \
			echo "Error: $$file is missing."; \
			echo "Run 'make perl5-sync' to import the latest Perl core tests before running the regex-thread gate."; \
			exit 1; \
		fi; \
	done

check-thread-core-test-sources:
	@for file in $(THREAD_CORE_TESTS); do \
		if [ ! -f "$$file" ]; then \
			echo "Error: $$file is missing."; \
			echo "Run 'make perl5-sync' to import the latest Perl core tests before running the complete thread gate."; \
			exit 1; \
		fi; \
	done
	@perl dev/tools/check_test_manifest.pl dev/test-manifests/threads-core.sha256

check-thread-ecosystem-test-sources:
	@for file in $(THREAD_ECOSYSTEM_UPSTREAM_TESTS); do \
		if [ ! -f "$$file" ]; then \
			echo "Error: $$file is missing from the current Perl compatibility corpus."; \
			exit 1; \
		fi; \
	done
	@printf '%s  %s\n' \
		4c7b58942d4c95f274be4bcc631981071688b5a11cae3bf132eb80e16c856a0e \
		dev/test-corpora/Moose-2.4000/t/todo_tests/moose_and_threads.t \
		| shasum -a 256 -c -

# Permanent Perl ithread compatibility gate used by Ubuntu pull-request CI.
# Full upstream distributions run on both backends with the default virtual
# carrier; lifecycle, stack, signal, wait, timeout, and deadlock coverage also
# runs on the platform carrier. Reports are retained under build/reports/threads.
test-thread-tooling:
	# The aggregate suite intentionally grows with each release-evidence tool.
	# Individual tests retain their own narrow bounds; allow the complete set
	# enough wall time on shared CI runners.
	timeout 120 prove dev/tools/tests/*.t

test-threads: check-java-gradle check-thread-test-sources test-thread-tooling
	@mkdir -p build/reports/threads
	JPERL_THREAD_MODE=virtual perl dev/tools/perl_test_runner.pl --strict-exit --jobs 8 --timeout 300 --output build/reports/threads/jvm-virtual.json $(THREAD_DIST_DIRS)
	JPERL_INTERPRETER=1 JPERL_THREAD_MODE=virtual perl dev/tools/perl_test_runner.pl --strict-exit --jobs 8 --timeout 300 --output build/reports/threads/interpreter-virtual.json $(THREAD_DIST_DIRS)
	JPERL_THREAD_MODE=platform perl dev/tools/perl_test_runner.pl --strict-exit --jobs 8 --timeout 300 --output build/reports/threads/platform-focused.json $(THREAD_PLATFORM_TESTS)

# Full same-commit direct/thread Perl-core matrix. Non-regex thread files and
# supported regex anchors remain strict. Partial direct regex files are owned by
# Phase 36; their unchanged wrappers must emit at least the same TAP and may not
# add failures, incompleteness, timeouts, or errors.
test-threads-core: check-java-gradle check-thread-core-test-sources
	@status=0; \
	JPERL_THREAD_MODE=virtual $(MAKE) test-threads-core-mode THREAD_CORE_REPORT_PREFIX=core-jvm-virtual || status=1; \
	JPERL_INTERPRETER=1 JPERL_THREAD_MODE=virtual $(MAKE) test-threads-core-mode THREAD_CORE_REPORT_PREFIX=core-interpreter-virtual || status=1; \
	exit $$status

test-threads-core-platform: check-java-gradle check-thread-core-test-sources
	@status=0; \
	JPERL_THREAD_MODE=platform $(MAKE) test-threads-core-mode THREAD_CORE_REPORT_PREFIX=core-jvm-platform || status=1; \
	JPERL_INTERPRETER=1 JPERL_THREAD_MODE=platform $(MAKE) test-threads-core-mode THREAD_CORE_REPORT_PREFIX=core-interpreter-platform || status=1; \
	exit $$status

test-threads-core-mode:
	@mkdir -p build/reports/threads/core; \
	status=0; \
	perl dev/tools/perl_test_runner.pl --jobs 4 --timeout 600 --output build/reports/threads/core/$(THREAD_CORE_REPORT_PREFIX)-direct.json $(THREAD_CORE_DIRECT_PARALLEL) || status=1; \
	for file in $(THREAD_CORE_DIRECT_SERIAL); do \
		name=$$(basename "$$file" .t); \
		perl dev/tools/perl_test_runner.pl --jobs 1 --timeout 900 --output "build/reports/threads/core/$(THREAD_CORE_REPORT_PREFIX)-direct-$$name.json" "$$file" || status=1; \
	done; \
	perl dev/tools/perl_test_runner.pl --strict-exit --jobs 4 --timeout 600 --output build/reports/threads/core/$(THREAD_CORE_REPORT_PREFIX)-nonregex.json $(THREAD_CORE_NONREGEX_WRAPPERS) || status=1; \
	perl dev/tools/perl_test_runner.pl --jobs 4 --timeout 600 --output build/reports/threads/core/$(THREAD_CORE_REPORT_PREFIX)-wrappers.json $(THREAD_CORE_REGEX_WRAPPER_PARALLEL) || status=1; \
	for file in $(THREAD_CORE_WRAPPER_SERIAL); do \
		name=$$(basename "$$file" .t); \
		perl dev/tools/perl_test_runner.pl --jobs 1 --timeout 900 --output "build/reports/threads/core/$(THREAD_CORE_REPORT_PREFIX)-wrapper-$$name.json" "$$file" || status=1; \
	done; \
	perl dev/tools/check_thread_core_parity.pl build/reports/threads/core $(THREAD_CORE_REPORT_PREFIX) || status=1; \
	exit $$status

# Shell-independent focused gate for windows-latest. It exercises the runtime
# and shared-storage thread suites directly through JUnit, without requiring a
# system Perl installation or fork-capable TAP harness.
test-threads-windows: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat testThreadsWindows --rerun-tasks --no-daemon
else
	./gradlew testThreadsWindows --rerun-tasks --no-daemon
endif

# Post-Joni preservation anchors. These are unchanged Perl core tests whose
# direct regex semantics are complete and whose threaded variants prove lexical
# debug state, user-property coordination, recursive definitions, character
# classes, callbacks, and snapshot ownership on both execution backends.
test-threads-regex: check-java-gradle check-thread-regex-test-sources
	@mkdir -p build/reports/threads
	JPERL_THREAD_MODE=virtual perl dev/tools/perl_test_runner.pl --strict-exit --jobs 5 --timeout 600 --output build/reports/threads/regex-jvm-virtual.json $(THREAD_REGEX_ANCHOR_TESTS)
	JPERL_INTERPRETER=1 JPERL_THREAD_MODE=virtual perl dev/tools/perl_test_runner.pl --strict-exit --jobs 5 --timeout 600 --output build/reports/threads/regex-interpreter-virtual.json $(THREAD_REGEX_ANCHOR_TESTS)

# Thread release gate: extend the PR gate to the complete platform-carrier
# distribution matrix. Together with test-threads this covers both backends on
# both carrier policies without making every pull request repeat all four runs.
test-threads-release: test-threads test-threads-core test-threads-core-platform test-threads-regex
	JPERL_THREAD_MODE=platform perl dev/tools/perl_test_runner.pl --strict-exit --jobs 8 --timeout 300 --output build/reports/threads/jvm-platform.json $(THREAD_DIST_DIRS)
	JPERL_INTERPRETER=1 JPERL_THREAD_MODE=platform perl dev/tools/perl_test_runner.pl --strict-exit --jobs 8 --timeout 300 --output build/reports/threads/interpreter-platform.json $(THREAD_DIST_DIRS)

# Slow ecosystem release gate. Net::SSLeay exercises native callbacks and
# concurrent SSL context ownership; DBIx::Class exercises DBI handle rejection,
# cloned package graphs, closures, and a large real-world ORM suite. This target
# is intentionally separate from pull-request CI because jcpan needs network
# access and the complete DBIx::Class run can take about 40 minutes.
test-threads-ecosystem: check-java-gradle check-thread-ecosystem-test-sources
	@mkdir -p build/reports/threads; \
	status=0; \
	JPERL_THREAD_MODE=virtual perl dev/tools/perl_test_runner.pl --strict-exit --jobs 4 --timeout 300 --output build/reports/threads/ecosystem-upstream-jvm.json $(THREAD_ECOSYSTEM_UPSTREAM_TESTS) || status=1; \
	JPERL_INTERPRETER=1 JPERL_THREAD_MODE=virtual perl dev/tools/perl_test_runner.pl --strict-exit --jobs 4 --timeout 300 --output build/reports/threads/ecosystem-upstream-interpreter.json $(THREAD_ECOSYSTEM_UPSTREAM_TESTS) || status=1; \
	exit $$status
	timeout 120 prove src/test/resources/unit/dbi_threads_runtime_ownership.t
	timeout 180 ./jperl src/test/resources/unit/dbi_threads_runtime_ownership.t
	timeout 180 ./jperl --interpreter src/test/resources/unit/dbi_threads_runtime_ownership.t
	JPERL_THREAD_MODE=platform timeout 180 ./jperl src/test/resources/unit/dbi_threads_runtime_ownership.t
	JPERL_INTERPRETER=1 JPERL_THREAD_MODE=platform timeout 180 ./jperl --interpreter src/test/resources/unit/dbi_threads_runtime_ownership.t
	JPERL_TEST_FILTER=61_threads-cb-crash $(MAKE) test-bundled-modules
	JPERL_TEST_FILTER=62_threads-ctx_new-deadlock $(MAKE) test-bundled-modules
	timeout 3600 ./jcpan --jobs 8 -t DBIx::Class

# Bundled CPAN module tests (XML::Parser, etc.)
# Tests live under src/test/resources/module/{ModuleName}/t/
test-bundled-modules: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat testModule --rerun-tasks
else
	./gradlew testModule --rerun-tasks
endif

# Bundled CPAN distroprefs: run jcpan -t for each distribution that ships a
# pref under PerlOnJava/CpanDistroprefs/ (DBI, Moo, Moose, IO::Async, ...).
# Slow (network + upstream test suites); logs under build/reports/.
# XML::LibXML is skipped unless INCLUDE_XML_LIBXML_IN_DISTROPREF_SMOKE=1 (see design doc).
# See dev/design/patch-and-cpan-prefs-layout.md and dev/tools/test-cpan-distroprefs.sh.
test-cpan-distroprefs: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat shadowJar -q
	bash dev/tools/test-cpan-distroprefs.sh
else
	./gradlew shadowJar -q
	bash dev/tools/test-cpan-distroprefs.sh
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

# Parallel unit tests via Gradle/JUnit (5 JVMs; last shard isolates heavy tests)
test-gradle-parallel: check-java-gradle
ifeq ($(OS),Windows_NT)
	gradlew.bat testUnitParallel --parallel --rerun-tasks
else
	./gradlew testUnitParallel --parallel --rerun-tasks
endif

# Parallel unit tests via Maven (5 JVMs; last shard isolates heavy tests)
test-maven-parallel:
ifeq ($(OS),Windows_NT)
	start /B mvn test -Pshard1 & start /B mvn test -Pshard2 & start /B mvn test -Pshard3 & start /B mvn test -Pshard4 & start /B mvn test -Pshard5
else
	mvn test -Pshard1 & mvn test -Pshard2 & mvn test -Pshard3 & mvn test -Pshard4 & mvn test -Pshard5 & wait
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
