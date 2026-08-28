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
} else {
    pass('experimental::equ is optional on older system Perl');
}

done_testing;
