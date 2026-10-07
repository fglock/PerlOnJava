use strict;
use warnings;
use feature 'switch';
no warnings 'deprecated';
no strict 'refs';
use Test::More tests => 4;

sub caller_from_core_reference {
    my @implicit = &CORE::caller;
    my @explicit_zero = &CORE::caller(0);
    is(scalar @implicit, 3,
        'CORE::caller code reference without an argument returns three values');
    is(scalar @explicit_zero, 11,
        'CORE::caller code reference with explicit zero returns extended details');
}

caller_from_core_reference();

my $continue = \&CORE::continue;
my $after_continue;
my $after_when;
CORE::given(1) {
    CORE::when(1) {
        $continue->();
        $after_continue = 'unreachable';
    }
    $after_when = 'reachable';
}
is($after_continue, undef,
    'CORE::continue code reference continues the enclosing given block');
is($after_when, 'reachable',
    'CORE::continue code reference resumes after the matching when clause');
