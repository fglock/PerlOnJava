use strict;
use warnings;
use Test::More tests => 5;

ok exists $warnings::Bits{uninitialized}, '%warnings::Bits exposes uninitialized';
is vec($warnings::Bits{uninitialized}, $warnings::Offsets{uninitialized}, 1),
    1, 'uninitialized enabled bit is set';
is vec($warnings::DeadBits{uninitialized}, $warnings::Offsets{uninitialized} + 1, 1),
    1, 'uninitialized fatal bit is set';
is vec($warnings::Bits{all}, $warnings::Offsets{uninitialized}, 1),
    1, 'all includes uninitialized';
is vec($warnings::Bits{syntax}, $warnings::Offsets{illegalproto}, 1),
    1, 'syntax includes illegalproto';
