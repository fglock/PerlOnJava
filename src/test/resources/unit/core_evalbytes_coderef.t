use strict;
use warnings;
use Test::More tests => 2;

my $evalbytes = \&CORE::evalbytes;
our $core_evalbytes_coderef_seen;
is($evalbytes->('6 * 7'), 42,
    'CORE::evalbytes code reference evaluates source');

{
    BEGIN { $^H{core_evalbytes_coderef} = 42 }
    $^H{core_evalbytes_coderef} = 75;
    $evalbytes->('BEGIN { $main::core_evalbytes_coderef_seen = $^H{core_evalbytes_coderef} }');
    is($core_evalbytes_coderef_seen, 42,
        'CORE::evalbytes code reference inherits compile-time hint hash');
}
