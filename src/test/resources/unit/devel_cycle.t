use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
use Devel::Cycle qw(find_cycle find_weakened_cycle);

my $self_cycle = {};
$self_cycle->{self} = $self_cycle;
my @cycles;
find_cycle($self_cycle, sub { push @cycles, shift });
is(scalar @cycles, 1, 'find_cycle detects a hash self-cycle');
is_deeply(
    [ map { [ $_->[0], $_->[1] ] } @{ $cycles[0] } ],
    [ [ 'HASH', 'self' ] ],
    'callback describes the hash edge in the cycle',
);

my ($left, $right);
$left = \$right;
$right = \$left;
@cycles = ();
find_cycle($left, sub { push @cycles, shift });
is(scalar @cycles, 1, 'find_cycle detects a scalar-reference cycle');
is_deeply(
    [ map { $_->[0] } @{ $cycles[0] } ],
    [ 'SCALAR', 'SCALAR' ],
    'scalar-reference cycle reports both scalar edges',
);

my $weak_cycle = {};
$weak_cycle->{self} = $weak_cycle;
weaken($weak_cycle->{self});
@cycles = ();
find_cycle($weak_cycle, sub { push @cycles, shift });
is(scalar @cycles, 0, 'find_cycle excludes weak edges');
find_weakened_cycle($weak_cycle, sub { push @cycles, shift });
is(scalar @cycles, 1, 'find_weakened_cycle includes weak edges');
ok($cycles[0][0][4], 'weakened cycle marks the weak edge');

ok(Devel::Cycle::HAVE_PADWALKER(),
    'PadWalker is available for closure inspection');
my $closure_cycle = {};
my $closure = sub { return $closure_cycle };
$closure_cycle->{closure} = $closure;
@cycles = ();
find_cycle($closure_cycle, sub { push @cycles, shift });
is(scalar @cycles, 1, 'find_cycle detects a captured lexical cycle');
is_deeply(
    [ map { $_->[0] } @{ $cycles[0] } ],
    [ 'HASH', 'CODE', 'SCALAR' ],
    'closure cycle reports hash, captured-code, and lexical-scalar edges',
);

done_testing;
