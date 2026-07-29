use strict;
use warnings;

my $test = 0;
sub check {
    my ($got, $expected, $name) = @_;
    ++$test;
    my $pass = $got == $expected;
    print(($pass ? 'ok' : 'not ok'), " $test - $name\n");
    print("# got $got, expected $expected\n") unless $pass;
}

print "1..6\n";

my $hash = {count => 0};
check(++($hash->{count}), 1, 'parenthesized hash lvalue pre-increment returns new value');
check($hash->{count}, 1, 'parenthesized hash lvalue pre-increment updates entry');
check(($hash->{count})++, 1, 'parenthesized hash lvalue post-increment returns old value');
check($hash->{count}, 2, 'parenthesized hash lvalue post-increment updates entry');

my @array = (4);
check(--($array[0]), 3, 'parenthesized array lvalue pre-decrement returns new value');
check($array[0], 3, 'parenthesized array lvalue pre-decrement updates element');
