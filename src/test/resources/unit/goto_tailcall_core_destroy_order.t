use strict;
use warnings;

my $test = 1;
sub is ($$) {
    my ($got, $expected) = @_;
    if (defined($got) && defined($expected) && $got eq $expected) {
        print "ok $test\n";
    } else {
        print "not ok $test - got ", (defined $got ? $got : 'undef'),
            ", expected ", (defined $expected ? $expected : 'undef'), "\n";
    }
    $test++;
}

print "1..6\n";
{
    my $i;
    package Foo;
    sub DESTROY { my $self = shift; ::is($self->[0], $i) }
    sub show    { ::is(+@_, 5) }
    sub start   { push @_, 1, 'foo', {}; goto &show }
    for (1 .. 3) { $i = $_; start(bless([$_]), 'bar') }
}
