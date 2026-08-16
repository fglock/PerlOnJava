use strict;
use warnings;
use Test::More tests => 4;

my ($hash_calls, $array_calls) = (0, 0);
sub hash_key  { ++$hash_calls;  return $_[0] }
sub array_key { ++$array_calls; return $_[0] }

sub exercise_hash {
    my %values = (target => 'value');
    is(delete $values{&hash_key}, 'value',
        'delete invokes an ampersand subroutine in a hash subscript');
    %values = (target => 'value');
    ok(exists $values{&hash_key},
        'exists invokes an ampersand subroutine in a hash subscript');
}

sub exercise_array {
    my @values = ('zero', 'one');
    is(delete $values[&array_key], 'one',
        'delete invokes an ampersand subroutine in an array subscript');
    @values = ('zero', 'one');
    ok(exists $values[&array_key],
        'exists invokes an ampersand subroutine in an array subscript');
}

exercise_hash('target');
exercise_array(1);

die "hash key callback count: $hash_calls" unless $hash_calls == 2;
die "array key callback count: $array_calls" unless $array_calls == 2;
