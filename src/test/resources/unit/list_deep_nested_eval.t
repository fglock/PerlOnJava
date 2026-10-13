use strict;
use warnings;

my $expression = '1';
$expression = "(1,$expression)" for 1 .. 100_000;
$expression = "() = $expression";

our $passed = 0;
sub is ($$) {
    my ($got, $expected) = @_;
    if (defined($got) && $got eq $expected) {
        $passed = 1;
        print "ok 1 - eval handles a deeply nested list assignment\n";
    } else {
        print "not ok 1 - eval handles a deeply nested list assignment\n";
    }
}

my $error;
{
    local $@;
    eval $expression;
    $error = $@;
}
is($error, '');
print "1..1\n";
die "deeply nested list assignment eval failed\n" unless $passed;
