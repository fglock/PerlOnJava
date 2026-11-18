use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Scalar::Util qw(weaken);
use Test::More;

{
    package LexicalScalarRefaliasOwnerLifetimeTarget;
    our $destroyed = 0;
    sub DESTROY { ++$destroyed }
}

my ($alias, $weak);
{
    my $source = bless {}, 'LexicalScalarRefaliasOwnerLifetimeTarget';
    $weak = $source;
    weaken($weak);
    \$alias = \$source;
}

ok(defined $weak,
    'outer lexical refalias retains the source scalar cell after its scope exits');
is($LexicalScalarRefaliasOwnerLifetimeTarget::destroyed, 0,
    'source referent is not destroyed while the outer alias remains live');

undef $alias;
ok(!defined $weak, 'dropping the final lexical alias releases the referent');
is($LexicalScalarRefaliasOwnerLifetimeTarget::destroyed, 1,
    'referent is destroyed exactly once after the final alias leaves');

done_testing();
