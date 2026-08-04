use strict;
use warnings;
use Test::More tests => 4;

{
    package NestedIsaOne;
    sub value { 'one' }
}
{
    package NestedIsaTwo;
    sub value { 'two' }
}
{
    package NestedIsaThree;
    sub value { 'three' }
}
{
    package NestedIsaChild;
    our @ISA = ('NestedIsaOne');
}

is(NestedIsaChild->value, 'one', 'initial nested-local ISA dispatch');
{
    local @NestedIsaChild::ISA = ('NestedIsaTwo');
    is(NestedIsaChild->value, 'two', 'outer localized ISA dispatch');
    {
        local @NestedIsaChild::ISA = ('NestedIsaThree');
        is(NestedIsaChild->value, 'three', 'inner localized ISA dispatch');
    }
    is(NestedIsaChild->value, 'two', 'inner localized ISA restoration invalidates lookup');
}
