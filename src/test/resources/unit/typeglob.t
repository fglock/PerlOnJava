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
use Test::More;

###################
# Typeglob operations

subtest 'Typeglob stringification' => sub {
    my $fh = *STDOUT;
    is($fh, "*main::STDOUT", "typeglob stringifies to name");

    my $fh2 = \*STDOUT;
    like($fh2, qr/^GLOB\(/, "typeglob reference stringifies to GLOB(...)");
};

subtest 'Using typeglobs as file handles' => sub {
    my $fh = *STDOUT;
    my $fh2 = \*STDOUT;

    ok(eval 'print $fh "# 123\n"; 1', "variable with typeglob can be used as file handle");
    ok(eval 'print $fh2 "# 124\n"; 1', "variable with typeglob reference can be used as file handle");
};

subtest 'References in code slots' => sub {
    my $res = eval q{
        BEGIN { $main::{X1} = \123; }
        return X1;
    };
    is($res, 123, "scalar reference in main code slot");

    $res = eval q{
        BEGIN { *main::X2 = \123; }
        return X2;
    };
    ok(!defined $res, "reference in code slot returns undef");
    like($@, qr/^Bareword "X2" not allowed/, "error message for bareword");
};

subtest 'References in package code slots' => sub {
    {
        package Testing;
        my $res = eval q{
            BEGIN { $Testing::{_X1} = \123; }
            return _X1;
        };
        ::is($res, 123, "scalar reference in Testing package code slot");
    }

    {
        package Testing2;
        my @res = eval q{
            BEGIN { $Testing2::{_X1} = [123, 456]; }
            return _X1;
        };
        ::is("@res", "123 456", "array reference in Testing2 package code slot");
    }
};

done_testing();
