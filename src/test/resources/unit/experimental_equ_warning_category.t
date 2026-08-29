use strict;
use warnings;
use Test::More;

my $accepted = eval {
    warnings->import('experimental::equ');
    1;
};

# Perl 5.34 (the system Perl used for test validation) predates this
# category. PerlOnJava must accept it because the synchronized core tests
# use the category.
if ($^X =~ m{(?:^|/)jperl(?:\z|\s)}) {
    ok($accepted, 'experimental::equ is a recognized warning category');
    my $operators = eval q{
        my ($a, $b) = (1, 1);
        $a === $b && $a !== 2 && $a equ '1' && 'x' neu 'y';
    };
    ok($operators, 'experimental equality operators parse and execute');
} else {
    pass('experimental::equ is optional on older system Perl');
    pass('experimental equality operators are optional on older system Perl');
}

done_testing;
