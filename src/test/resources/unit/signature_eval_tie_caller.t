use v5.36;
use strict;
use warnings;
use FindBin;
use lib "$FindBin::Bin/signature_eval_tie_caller";
use Test::More tests => 1;
use Readonly;

no warnings 'experimental::signatures';
no warnings 'experimental::args_array_with_signatures';

sub brent ($func, $state) {
    Readonly my $interp_coef => 3;
    return $interp_coef;
}

sub find_root ($func, $x1, $x2, %params) {
    return brent($func, {});
}

is(find_root(sub { 1 }, 0, 1), 3,
    'tied scalar constructor sees its Perl caller through nested signatures');
