use strict;
use warnings;

use Test::More tests => 4;
use Storable qw(dclone);

{
    package PositiveIntegerTie;

    sub TIESCALAR { bless { value => 1 }, shift }
    sub FETCH { $_[0]{value} }
    sub STORE {
        die "positive integer required\n"
            unless defined $_[1] && $_[1] =~ /\A[1-9][0-9]*\z/;
        $_[0]{value} = $_[1];
    }
}

my $container = [0];
tie $container->[0], 'PositiveIntegerTie';
my $clone = dclone($container);

isa_ok(tied($clone->[0]), 'PositiveIntegerTie',
    'dclone preserves tie magic on a scalar array element');

eval { $clone->[0] = 2 };
is($@, '', 'valid assignment to cloned tied scalar succeeds');
is($clone->[0], 2, 'cloned tied scalar stores valid value');

eval { $clone->[0] = 'invalid' };
like($@, qr/positive integer required/,
    'cloned tied scalar still rejects invalid values');
