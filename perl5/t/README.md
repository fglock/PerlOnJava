`perl5/t/`

This directory is a placeholder for the original Perl test suite.

You can copy Perl test files here to verify their behavior under PerlOnJava.
To run the tests, use the following commands:

    rm -rf perl5

    git clone https://github.com/Perl/perl5.git

    git checkout perl5

    perl dev/tools/perl_test_runner.pl --output out.json perl5/t

