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

{
    package Local::HintHash::Importer;
    sub import {
        $^H{'Local::HintHash::Importer'} = bless {}, 'Local::HintHash::Guard';
    }
}
$INC{'Local/HintHash/Importer.pm'} = __FILE__;

$ok = eval q{
    BEGIN {
        package Local::HintHash::Consumer;
        use Local::HintHash::Importer;
    }
    1;
};

ok($ok, 'eval with a use-time hint guard succeeds');
is($destroyed, 2, 'a call-site hint snapshot does not retain a discarded guard');

done_testing;
