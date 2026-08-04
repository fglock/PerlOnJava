use strict;
use warnings;
use Test::More tests => 4;

{
    package LocalIsaBase;
    sub value { 'base' }
}
{
    package LocalIsaOther;
    sub value { 'other' }
}
{
    package LocalIsaChild;
    our @ISA = ('LocalIsaBase');
}

is(LocalIsaChild->value, 'base', 'initial ISA dispatches to the base class');
{
    local @LocalIsaChild::ISA = ('LocalIsaOther');
    is(LocalIsaChild->value, 'other', 'localized ISA invalidates method lookup');
}
is(LocalIsaChild->value, 'base', 'restoring localized ISA invalidates method lookup');
is_deeply(\@LocalIsaChild::ISA, ['LocalIsaBase'], 'localized ISA restores its contents');
