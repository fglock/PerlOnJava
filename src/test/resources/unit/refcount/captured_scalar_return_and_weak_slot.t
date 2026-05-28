use strict;
use warnings;

use Test::More;
use B qw(svref_2object);
use Scalar::Util qw(weaken);

{
    package POJCapturedScalarReturn;
    sub new { bless {}, shift }
}

sub retain_like {
    my $self = shift;
    push @{ $self->{callbacks} }, sub { undef $self };
    return $self;
}

sub consume_deeply {
    my ($got, $expected) = @_;
    my %copy = %$expected;
    return $got == $expected && exists $copy{callbacks};
}

my $object = POJCapturedScalarReturn->new;
$object->{callbacks} = [];

ok consume_deeply(retain_like($object), $object), 'captured scalar return compares as the same object';
is svref_2object($object)->REFCNT, 2, 'captured scalar return does not release closure owner';

my $slot_object = bless {}, 'POJWeakScalarSlot';
my $array = [$slot_object];
my $slot_ref = \$array->[0];
weaken($slot_ref);

ok defined($slot_ref), 'weak scalar ref to live array slot remains defined';
undef $$slot_ref;
ok !defined($array->[0]), 'weak scalar ref can still clear the original array slot';
is svref_2object($slot_object)->REFCNT, 1, 'clearing through weak scalar ref releases slot owner';

done_testing;
