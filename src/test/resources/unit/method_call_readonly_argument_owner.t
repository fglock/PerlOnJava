use strict;
use warnings;
use Test::More;

{
    package MethodCallReadonlyArgumentOwner;

    sub echo { return $_[1] }
    sub overwrite { $_[1] = 99; return $_[1] }
    sub read_reference { return ${$_[1]} }
    sub many_arguments { return join ':', @_[1 .. $#_] }
}

my $object = bless {}, 'MethodCallReadonlyArgumentOwner';
for my $iteration (1 .. 8) {
    is($object->echo(37), 37,
        "cached integer literal remains readable on repeated method call $iteration");
    is($object->echo('small'), 'small',
        "string literal remains readable on repeated method call $iteration");
}

my $ok = eval { $object->overwrite(37); 1 };
ok(!$ok, 'a method cannot mutate a cached integer literal argument');
like($@, qr/Modification of a read-only value attempted/,
    'cached integer literal mutation retains Perl read-only behavior');

is($object->many_arguments(qw(one two three four five six)),
    'one:two:three:four:five:six',
    'literal arguments remain correct beyond the direct-call inline boundary');

my $referent = 'mutable';
is($object->read_reference(\$referent), 'mutable',
    'reference argument ownership remains intact');

done_testing();
