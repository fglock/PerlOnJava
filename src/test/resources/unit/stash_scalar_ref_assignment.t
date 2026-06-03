use strict;
use warnings;
use Test::More tests => 4;

{
    package StashScalarRefTarget;
    our $x = 0;
    our $y = 0;
}

my $name = 'x';
${*StashScalarRefTarget::}{$name} = \do { my $v = 1 };

is($StashScalarRefTarget::x, 1, 'stash scalar-ref assignment updates existing scalar slot');
is(${${*StashScalarRefTarget::}{$name}}, 1, 'stash entry scalar slot sees aliased value');
my $call_ok = eval { StashScalarRefTarget::x(); 1 };
ok(!$call_ok, 'stash scalar-ref assignment does not create a code slot');

$StashScalarRefTarget::{y} = \do { my $v = 2 };
is($StashScalarRefTarget::y, 2, 'direct stash scalar-ref assignment updates existing scalar slot');
