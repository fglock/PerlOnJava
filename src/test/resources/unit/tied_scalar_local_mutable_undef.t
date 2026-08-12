use strict;
use warnings;
use Test::More tests => 5;

{
    package LocalSelectTie;

    sub TIESCALAR { bless [], $_[0] }
    sub FETCH { select }
    sub STORE { select $_[1] }
}

tie our $selected, 'LocalSelectTie';

open my $first, '>', \my $first_output;
open my $second, '>', \my $second_output;

{
    local $selected = $first;
    print 'first';
    is($first_output, 'first', 'localized tied scalar selects first handle');

    {
        local $selected = $second;
        print 'second';
        is($second_output, 'second', 'nested localization selects second handle');
    }

    print '-again';
    is($first_output, 'first-again', 'nested scope restores first handle');
}

is(select, 'main::STDOUT', 'outer scope restores the original selected handle');
ok(tied($selected), 'localization preserves tied scalar magic');
