use strict;
use warnings;
use Test::More;

our $target = 'initial';
our $alias;
*alias = \$target;

is($alias, 'initial', 'typeglob scalar-reference assignment aliases its SCALAR slot');
$alias = 'changed through alias';
is($target, 'changed through alias', 'the aliased scalar slot remains shared');

{
    no strict 'subs';
    my $bareword_ref = \_;
    is($$bareword_ref, '_', 'refgen keeps a bare underscore as a bareword, not @_');

    is(${*inline_alias = \_}, '_',
        'immediate scalar dereference of a typeglob assignment preserves its scalar slot');
}

done_testing;
