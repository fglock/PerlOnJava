# Test Directory

This directory contains Perl test files that are not suitable for execution in the automated CI/CD test suite. These tests require access to the file system or other resources that are not available in the CI/CD environment.

## Purpose

The tests in this directory are designed to verify functionality that depends on specific system resources or configurations. They are intended to be run manually in a local development environment where these resources are available.

## Running Tests

**TODO**

To include the /test directory in your local build, run one of the following commands:

```
mvn test -DincludeTestDir=true
```

```
gradlew test -DincludeTestDir=true
```

## Automated Tests

For tests that can be executed in the CI/CD environment, please refer to the automated test suite located in the src/test/resources/ directory. These tests are designed to run without requiring special system resources.


