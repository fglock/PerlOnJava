.PHONY: all clean test test-unit test-all test-gradle test-gradle-unit test-gradle-all build run wrapper dev

all: build

wrapper:
	gradle wrapper

# Standard build - incremental compilation (fast)
build: wrapper
ifeq ($(OS),Windows_NT)
	gradlew.bat build
else
	./gradlew build
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
# Uses perl_test_runner.pl with TAP output and parallel execution
test-unit:
	@echo "Running fast unit tests..."
	perl dev/tools/perl_test_runner.pl --jobs 8 --timeout 10 src/test/resources/unit

# Comprehensive tests including all modules (slower)
# Runs all tests from src/test/resources/
test-all:
	@echo "Running comprehensive test suite..."
	perl dev/tools/perl_test_runner.pl --jobs 8 --timeout 60 --output test_results.json src/test/resources

# Alternative: Run tests using JUnit/Gradle (for CI/CD integration)
test-gradle: test-gradle-unit

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

