use strict;
use warnings;
use Test::More tests => 4;
use Crypt::Argon2 qw(argon2i_pass argon2i_raw argon2_verify argon2_implementation);

my $encoded = argon2i_pass('password', 'somesalt', 2, '256k', 1, 32);
is($encoded, '$argon2i$v=19$m=256,t=2,p=1$c29tZXNhbHQ$iekCn0Y3spW+sCcFanM2xBT63UP2sghkUoHLIUpWRS8',
    'Argon2i encoded vector');
is(unpack('H*', argon2i_raw('password', 'somesalt', 2, '256k', 1, 32)),
    '89e9029f4637b295beb027056a7336c414fadd43f6b208645281cb214a56452f',
    'Argon2i raw vector');
ok(argon2_verify($encoded, 'password'), 'Argon2 verifies matching password');
ok(argon2_implementation(), 'implementation is reported');
