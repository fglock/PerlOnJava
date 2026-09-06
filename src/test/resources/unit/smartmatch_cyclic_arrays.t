use strict;
use warnings;
use Test::More;

no warnings 'experimental::smartmatch';

my $self = [];
push @$self, $self;
ok($self ~~ $self, 'a self-referential array smartmatches itself');

my $other = [];
push @$other, $other;
ok(!($self ~~ $other), 'distinct self-referential arrays do not smartmatch');

ok([ [ 'foo' ], [ 'bar' ] ] ~~ [ qr/o/, qr/a/ ],
    'array-reference smartmatch compares corresponding nested elements');
ok(qr/foo/ ~~ [ 'foo' ], 'a regex smartmatches an array candidate');

done_testing;
