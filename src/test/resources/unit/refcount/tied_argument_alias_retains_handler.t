use strict;
use warnings;
use Test::More tests => 3;

our @events;
my @warnings;

{
    package AliasedTieHash;

    sub TIEHASH { push @main::events, 'TIEHASH'; bless {}, shift }
    sub FETCH { push @main::events, 'FETCH'; return undef }
    sub STORE { push @main::events, 'STORE' }
    sub UNTIE { push @main::events, 'UNTIE' }
    sub DESTROY { push @main::events, 'DESTROY' }

    package AliasedTieArray;

    sub TIEARRAY { push @main::events, 'TIEARRAY'; bless {}, shift }
    sub FETCH { push @main::events, 'FETCH'; return undef }
    sub STORE { push @main::events, 'STORE' }
    sub FETCHSIZE { push @main::events, 'FETCHSIZE'; return 0 }
    sub UNTIE { push @main::events, 'UNTIE' }
    sub DESTROY { push @main::events, 'DESTROY' }

    package main;

    local $SIG{__WARN__} = sub { push @warnings, @_ };

    our %tied_hash;
    our @tied_array;

    sub inspect_hash {
        push @events, 'inside before untie';
        untie %main::tied_hash;
        push @events, defined($_[0]) ? 'inside after defined' : 'inside after undef';
    }

    sub inspect_array {
        push @events, 'inside before untie';
        untie @main::tied_array;
        push @events, defined($_[0]) ? 'inside after defined' : 'inside after undef';
    }

    tie %tied_hash, 'AliasedTieHash';
    inspect_hash($tied_hash{missing});
    push @events, 'after call';
    is_deeply(
        \@events,
        [
            'TIEHASH', 'inside before untie', 'UNTIE', 'FETCH',
            'inside after undef', 'DESTROY', 'after call',
        ],
        'a tied hash handler survives untie through an aliased element argument',
    );

    @events = ();
    tie @tied_array, 'AliasedTieArray';
    inspect_array($tied_array[0]);
    push @events, 'after call';
    is_deeply(
        \@events,
        [
            'TIEARRAY', 'inside before untie', 'UNTIE', 'FETCH',
            'inside after undef', 'DESTROY', 'after call',
        ],
        'a tied array handler survives untie through an aliased element argument',
    );

    is_deeply(\@warnings, [], 'the argument magic owner does not cause an untie warning');
}

done_testing();
