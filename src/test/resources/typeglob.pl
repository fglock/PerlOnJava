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
use strict;
use warnings;
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

my $res =
eval q{
    BEGIN { $main::{X1} = \123; }
    return X1;
};
print "not " if $res ne 123;
say "ok # reference in a code slot <$res> <" . substr($@, 0, 20) . ">";

$res =
eval q{
    BEGIN { *main::X2 = \123; }
    return X2;
};
print "not " if defined $res;
say "ok # reference in a code slot <" . ($res // "") . "> <" . substr($@, 0, 20) . ">";

# Bareword "X" not allowed while "strict subs" in use at -e line 2, near "\", X "
say "not" if $@ !~ /^Bareword "X2" not allowed/;
say "ok # error message <" . substr($@, 0, 20) . ">";

{
package Testing;
my $res =
eval q{
    BEGIN { $Testing::{_X1} = \123; }
    return _X1;
};
print "not " if $res ne 123;
say "ok # scalar reference in a code slot <$res> <" . substr($@, 0, 20) . ">";
}

{
package Testing2;
my @res =
eval q{
    BEGIN { $Testing2::{_X1} = [123, 456]; }
    return _X1;
};
print "not " if "@res" ne "123 456";
say "ok # array reference in a code slot <@res> <" . substr($@, 0, 20) . ">";
}

