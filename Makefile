.PHONY: all clean test build run wrapper

all: build

wrapper:
	gradle wrapper

build: wrapper
ifeq ($(OS),Windows_NT)
	gradlew.bat build
else
	./gradlew build
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

