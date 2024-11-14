#
#   This Perl script is part of the project's examples and demonstrates various Perl features.
#   It is located in the src/test/resources directory.
#
#   Perl test files in src/test/resources are executed during the build process by Maven/Gradle.
#   This ensures that the Perl scripts are tested for correctness as part of the continuous integration pipeline.
#
#   To run the tests manually, you can use the following commands:
#     - For Maven: `mvn test`
#     - For Gradle: `gradle test`
#
#   These commands will compile the Java code, run the Java and Perl tests, and generate test reports.
#
#   Ensure that any new Perl scripts added to src/test/resources follow the project's testing conventions.
#

# use 5.32.0;
use feature 'say';
use feature 'isa';

###################
# Typeglob operations

my $fh = *STDOUT;
print "not " if $fh ne "*main::STDOUT"; say "ok # typeglob stringifies to name: $fh";

my $fh2 = \*STDOUT;
print "not " if substr($fh2, 0, 5) ne "GLOB("; say "ok # typeglob reference stringifies to name: $fh2";

eval 'print $fh "# 123\n";' or print "not "; say "ok # variable with typeglob can be used as file handle";

eval 'print $fh2 "# 124\n";' or print "not "; say "ok # variable with typeglob reference can be used as file handle";

