use strict;
use warnings;
use Test::More tests => 1;

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    $::{prototype_whitespace_comparison_target} = ' $ $ ';
    eval 'sub prototype_whitespace_comparison_target($$) { 1 }';
}

is($warning, '', 'whitespace-only prototype differences do not warn');
