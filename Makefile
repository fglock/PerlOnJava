.PHONY: all clean test test-unit test-all test-gradle test-gradle-unit test-gradle-all test-gradle-parallel test-maven-parallel build run wrapper dev ci

# If FORCE_TESTS=1, Gradle test tasks will be executed with --rerun-tasks to avoid
# false positives from UP-TO-DATE/cached test results.
#
# Default is 1 because `make` is used frequently and should not give a false
# impression of correctness.
FORCE_TESTS ?= 1
RERUN_TASKS_FLAG := $(if $(filter 1,$(FORCE_TESTS)),--rerun-tasks,)

all: build

# CI build - optimized for CI/CD environments
ci: wrapper
ifeq ($(OS),Windows_NT)
	mvn clean test -B
else
	./gradlew build --no-daemon --stacktrace
endif

wrapper:
	@test -f ./gradlew || gradle wrapper

# Standard build - incremental compilation with parallel tests (4 JVMs)
build: wrapper
ifeq ($(OS),Windows_NT)
	gradlew.bat classes testUnitParallel --parallel $(RERUN_TASKS_FLAG) shadowJar
else
	./gradlew classes testUnitParallel --parallel $(RERUN_TASKS_FLAG) shadowJar
endif

# Development build - forces recompilation (use during active development)
dev: wrapper
ifeq ($(OS),Windows_NT)
	gradlew.bat clean compileJava installDist
else
	./gradlew clean compileJava installDist
endif

# Default test target - fast unit tests using perl_test_runner.pl
test: test-unit

# Fast unit tests only (from src/test/resources/unit/ directory)
# Uses Gradle's testUnitParallel (same as default make build)
test-unit: wrapper
ifeq ($(OS),Windows_NT)
	gradlew.bat testUnitParallel --parallel $(RERUN_TASKS_FLAG)
else
	./gradlew testUnitParallel --parallel $(RERUN_TASKS_FLAG)
endif

# Convenience targets to force tests regardless of FORCE_TESTS env var.
test-unit-force:
	$(MAKE) FORCE_TESTS=1 test-unit

build-force:
	$(MAKE) FORCE_TESTS=1 build

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
test-gradle-unit: wrapper
ifeq ($(OS),Windows_NT)
	gradlew.bat testUnit --rerun-tasks
else
	./gradlew testUnit --rerun-tasks
endif

# All tests via Gradle/JUnit
test-gradle-all: wrapper
ifeq ($(OS),Windows_NT)
	gradlew.bat testAll --rerun-tasks
else
	./gradlew testAll --rerun-tasks
endif

# Parallel unit tests via Gradle/JUnit (4 JVMs)
test-gradle-parallel: wrapper
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

clean: wrapper
ifeq ($(OS),Windows_NT)
	gradlew.bat clean
else
	./gradlew clean
endif

deb: wrapper
ifeq ($(OS),Windows_NT)
	gradlew.bat buildDeb
else
	./gradlew buildDeb
endif

