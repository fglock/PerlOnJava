use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

{
    package SwitchBreakCleanup;
    sub new { bless { flag => \($_[1]) }, $_[0] }
    sub DESTROY { ${ $_[0]->{flag} }++ }
}

my $destroyed = 0;
my $result = do {
    given (1) {
        when (1) {
            my $temporary = SwitchBreakCleanup->new($destroyed);
            break;
        }
    }
};
is $result, undef, 'break from given has no result';
is $destroyed, 1, 'break tears down a nested when lexical';

my @values = (1, do {
    given ('x') {
        2, 3, do {
            when (/[a-z]/) {
                4, 5, 6, break;
            }
        }
    }
});
is "@values", '1', 'break clears intermediate list values';

done_testing;
