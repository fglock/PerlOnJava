.PHONY: all clean test build run

all: build

build:
	./gradlew build

test:
	./gradlew test

clean:
	./gradlew clean

deb:
	./gradlew buildDeb
