use strict;
use warnings;
use Test::More;

{
    package MethodCallArgumentContainerSemantics;

    sub echo { shift; return join '|', @_ }

    sub replace_last_argument {
        $_[$#_] = 'updated through alias';
        return $_[$#_];
    }

    sub reshape_arguments {
        shift @_;
        my $first = shift @_;
        unshift @_, 'inserted';
        push @_, 'appended';
        return join '|', $first, @_;
    }

    sub retain_arguments { return \@_ }

    sub duplicate_arguments {
        shift @_;
        push @_, @_;
        return join '|', @_;
    }
}

my $object = bless {}, 'MethodCallArgumentContainerSemantics';

is($object->echo('a', 'b', 'c'),
    'a|b|c',
    'method call preserves all argument values below the inline boundary');

is($object->echo(), '',
    'zero-argument method calls preserve the invocant-only argument list');

is($object->echo(qw(a b c d e)), 'a|b|c|d|e',
    'larger literal method argument lists keep the ordinary list representation');

my $aliased = 'before';
is($object->replace_last_argument($aliased), 'updated through alias',
    'method receives the caller scalar through its argument slot');
is($aliased, 'updated through alias',
    'assignment through @_ updates the caller scalar');

my @many = qw(a b c d e f);
is($object->echo(@many),
    'a|b|c|d|e|f',
    'method call preserves arguments beyond the inline boundary');

my $last = 'before';
$object->replace_last_argument('first', $last);
is($last, 'updated through alias',
    'alias writes remain correct when the argument list is promoted');

is($object->reshape_arguments('one', 'two'),
    'one|inserted|two|appended',
    'shift, unshift, and push reshape the method argument list');

is($object->duplicate_arguments('one', 'two'),
    'one|two|one|two',
    'self-appending @_ snapshots its elements before list growth');

my $escaped = 'before';
my $args_ref = $object->retain_arguments('kept', $escaped);
is(ref($args_ref), 'ARRAY', 'a returned reference retains the @_ array');
is($args_ref->[0], $object, 'the escaped @_ array retains the invocant');
is($args_ref->[1], 'kept', 'the escaped @_ array retains the first argument');
is($args_ref->[2], 'before', 'the escaped @_ array retains the final argument');
$args_ref->[2] = 'updated through escaped @_';
is($escaped, 'updated through escaped @_',
    'an escaped @_ reference retains aliases to caller scalars');

done_testing();
