use strict;
use warnings;
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';

our $tests = 0;
our $failed = 0;
sub is ($$@) {
    my ($got, $expected, $name) = @_;
    ++$tests;
    if (defined($got) && $got eq $expected) {
        print "ok $tests - $name\n";
    } else {
        $failed = 1;
        print "not ok $tests - $name\n";
        print "# got: ", defined($got) ? $got : '<undef>', "\n";
        print "# expected: $expected\n";
    }
}

sub foo { 42 }

{
    my sub foo;
    *AutoloadTestSuper::blah = \&foo;
    sub AutoloadTestSuper::AUTOLOAD {
        is $AutoloadTestSuper::AUTOLOAD, 'AutoloadTestSuper::blah',
            'inherited lexical stub invokes AUTOLOAD';
    }
    @AutoloadTest::ISA = 'AutoloadTestSuper';
    AutoloadTest->blah;
}

print "1..1\n";
die "expected inherited lexical stub AUTOLOAD assertion to run\n"
    unless $tests == 1 && !$failed;
