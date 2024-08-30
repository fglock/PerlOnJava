# Test Resources Directory

This directory contains Perl test files that are part of the automated test suite.
These tests are executed during the build process by Maven/Gradle to ensure the correctness of the Perl code.

Note:

- The Perl test files in this directory are automatically run during the build process.
  This ensures that any changes to the Perl scripts are validated as part of the continuous integration pipeline.

- To run the tests manually, you can use the following commands:
    - For Maven: `mvn test`
    - For Gradle: `gradle test`

  These commands will compile the Java code, run the Java and Perl tests, and generate test reports.

- Ensure that any new Perl test files added to this directory follow the project's testing conventions.

- If you add new test files, consider documenting their purpose and the scenarios they cover to help other developers
  understand the tests.

- Example Perl scripts that demonstrate various features and capabilities of Perl are located in the examples directory.
  These example scripts are not part of the automated test suite and are provided for educational and illustrative
  purposes.


