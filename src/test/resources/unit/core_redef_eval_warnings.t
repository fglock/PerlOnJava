use strict;
use warnings;
use Test::More tests => 2;

my $warnings = '';
{
    local $SIG{__WARN__} = sub { $warnings .= join '', @_ };
    {
        local $^W = 0;
        eval q{sub core_redef_eval_warning () { 1 } sub core_redef_eval_warning { 1 }};
    }
}

like($warnings, qr/Prototype mismatch: sub main::core_redef_eval_warning \(\) vs none/,
    'eval retains prototype mismatch warning when global warnings are disabled');
like($warnings, qr/Constant subroutine core_redef_eval_warning redefined/,
    'eval retains constant subroutine redefine warning');
