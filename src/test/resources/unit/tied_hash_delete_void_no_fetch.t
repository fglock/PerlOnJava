use strict;
use warnings;
use Test::More tests => 4;
{
    package StoreOnlyHashValue;
    our $stores = 0;
    sub TIESCALAR { bless [], shift }
    sub STORE { ++$stores }
}
my $env_key = 'PERLONJAVA_DELETE_VOID_FETCH_TEST';
tie $ENV{$env_key}, 'StoreOnlyHashValue';
$ENV{$env_key} = 78;
my $success = eval { delete $ENV{$env_key}; 1 };
ok($success, 'void delete of tied %ENV element does not FETCH');
is($@, '', 'tied %ENV element can be deleted without a FETCH method');
tie $^H{perlonjava_delete_void_fetch_test}, 'StoreOnlyHashValue';
$^H{perlonjava_delete_void_fetch_test} = 78;
$success = eval { delete $^H{perlonjava_delete_void_fetch_test}; 1 };
ok($success, 'void delete of tied %^H element does not FETCH');
is($@, '', 'tied %^H element can be deleted without a FETCH method');
