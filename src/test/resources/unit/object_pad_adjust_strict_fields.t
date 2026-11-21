use strict;
use warnings;
use Test::More;

if (!eval { require Object::Pad; 1 }) {
    plan skip_all => 'Object::Pad is not installed in system Perl';
}

my $source = <<'PERL';
use v5.26;
use Object::Pad 0.800;

class Test::ObjectPad::StrictAdjust :repr(HASH) {
    field $_left;
    field $_right;

    ADJUST { ($_left, $_right) = ('left', 'right'); }
    method value { $_left . ':' . $_right }
}

our $value = Test::ObjectPad::StrictAdjust->new->value;
PERL

our $value;
my $compiled = eval $source;
ok($compiled, 'strict ADJUST field access compiles') or diag $@;
is($value, 'left:right', 'strict ADJUST initializes fields on system Perl semantics')
    if $compiled;

done_testing();
