use strict;
use warnings;
use Test::More tests => 5;
{
    package StoreOnly;
    our $stores = 0;
    sub TIESCALAR { bless [], shift }
    sub TIEARRAY { bless [], shift }
    sub STORE { ++$stores }
}
tie my $scalar, 'StoreOnly';
tie my @array, 'StoreOnly';
sub scalar_tail { $scalar = 37 }
sub array_tail { $array[0] = 42 }
my $success = eval { scalar_tail(); 1 };
ok($success, 'void call does not FETCH a final tied scalar assignment');
is($@, '', 'STORE-only scalar succeeds without a FETCH method');
$success = eval { array_tail(); 1 };
ok($success, 'void call does not FETCH a final tied array element assignment');
is($@, '', 'STORE-only array succeeds without a FETCH method');
is($StoreOnly::stores, 2, 'both assignments invoke STORE once');
