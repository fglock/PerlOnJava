use strict;
use warnings;
use Test::More;

# Perl's exists operator leaves its final key alone, but vivifies each
# missing intermediate hash while walking a nested hash-reference path.
my $capabilities = { alwaysMatch => {} };

ok !exists $capabilities->{alwaysMatch}->{'moz:firefoxOptions'}->{args},
    'nested exists returns false for a missing final key';
is ref $capabilities->{alwaysMatch}->{'moz:firefoxOptions'}, 'HASH',
    'nested exists vivifies the missing intermediate hash';
is_deeply $capabilities->{alwaysMatch}->{'moz:firefoxOptions'}, {},
    'nested exists does not create the final key';

done_testing;
