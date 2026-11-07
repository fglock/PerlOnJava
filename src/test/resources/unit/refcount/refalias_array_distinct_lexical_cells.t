use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Scalar::Util qw(weaken);
use Test::More;

{
    package RefaliasArrayDistinctLexicalTarget;
    our %destroyed;
    sub DESTROY { ++$destroyed{$_[0]{id}} }
}

my (@first, @second);
my ($weak_first, $weak_second);
{
    my $object = bless {id => 'first'}, 'RefaliasArrayDistinctLexicalTarget';
    $weak_first = $object;
    weaken($weak_first);
    \(@first) = \($object);
}
{
    my $object = bless {id => 'second'}, 'RefaliasArrayDistinctLexicalTarget';
    $weak_second = $object;
    weaken($weak_second);
    \(@second) = \($object);
}

ok(defined $weak_first,
    'the first aliased cell survives a later same-named lexical declaration');
ok(defined $weak_second, 'the second aliased cell survives its lexical scope');
is($first[0]{id}, 'first', 'the first array still refers to its original cell');
is($second[0]{id}, 'second', 'the second array refers to its own cell');

@second = ();
ok(!defined $weak_second, 'clearing the second array releases its referent');
ok(defined $weak_first, 'the first array keeps its referent alive');
is($RefaliasArrayDistinctLexicalTarget::destroyed{first} // 0, 0,
    'the first referent was not destroyed with the second');

@first = ();
ok(!defined $weak_first, 'clearing the first array releases its referent');
is($RefaliasArrayDistinctLexicalTarget::destroyed{first}, 1,
    'the first referent is destroyed exactly once');

done_testing;
