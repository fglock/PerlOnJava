use strict;
use warnings;
use Test::More;

our $destroyed = 0;

{
    package Local::HintHash::Guard;
    sub DESTROY { $main::destroyed++ }
}

my $ok = eval q{
    BEGIN {
        $^H{'Local::HintHash::Guard'} = bless {}, 'Local::HintHash::Guard';
    }
    sub local_hint_hash_scope_guard { 1 }
    1;
};

ok($ok, 'eval with a compile-time hint guard succeeds');
is($destroyed, 1, 'discarding an eval hint hash releases its guard');

done_testing;
