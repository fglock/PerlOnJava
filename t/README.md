`t/`

This directory is a placeholder for the original Perl test suite.

You can copy Perl test files here to verify their behavior under PerlOnJava:

    rm -rf perl5
    rm -rf t
    git clone https://github.com/Perl/perl5.git

    rsync -a perl5/t/ t/
    git checkout t

To run the tests, use the following commands:

    perl dev/tools/perl_test_runner.pl --output out.json t

