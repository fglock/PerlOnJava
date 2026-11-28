use strict;
use warnings;
use Test::More tests => 3;
our $during_eval;

{
    package ConstantCodeRefDowngrade;
    use constant FOO => 1;

    ::like "$ConstantCodeRefDowngrade::{FOO}", qr/SCALAR/,
        'constant starts in compact stash form';
    eval q{
        my $code = \&FOO;
        $main::during_eval = "$ConstantCodeRefDowngrade::{FOO}";
    };
    ::like $main::during_eval, qr/^\*/,
        'taking a code reference upgrades the constant glob during eval';
    ::like "$ConstantCodeRefDowngrade::{FOO}", qr/SCALAR/,
        'freeing eval ops permits constant glob downgrade';
}
