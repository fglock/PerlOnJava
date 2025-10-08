.PHONY: all clean test build run wrapper dev

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

test: wrapper
ifeq ($(OS),Windows_NT)
	gradlew.bat test --rerun-tasks
else
	./gradlew test --rerun-tasks
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

