.PHONY: all clean test build run

all: build

build:
	./gradlew build

test:
	./gradlew test --rerun-tasks

clean:
	./gradlew clean

deb:
	./gradlew buildDeb
