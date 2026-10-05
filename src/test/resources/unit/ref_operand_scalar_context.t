use strict;
use warnings;
use Test::More tests => 1;

sub context_sensitive_value {
    return wantarray ? ('list value', 'extra list value') : bless {}, 'Local::ScalarResult';
}

is(ref context_sensitive_value(), 'Local::ScalarResult',
    'ref evaluates its operand in scalar context inside a list-context call');
