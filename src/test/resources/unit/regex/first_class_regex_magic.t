use strict;
use warnings;
use Test::More;
use Scalar::Util qw(reftype);

my $regex_type;
my $slot;
sub {
    $_[0] = ${qr/abc/};
    my $copy = $_[0];
    $regex_type = reftype(\$copy);
}->($slot);
is($regex_type, 'REGEXP', 'copying a dereferenced qr scalar preserves regex magic');

my $plain = 'abc';
my $plain_copy = $plain;
is(reftype(\$plain_copy), 'SCALAR', 'ordinary scalar copies remain ordinary scalars');

my $argument_copy_type;
sub {
    $_[0] = ${qr/abc/};
    my $copy = $_[0];
    $argument_copy_type = reftype(\$copy);
}->($_[0]);
is($argument_copy_type, 'REGEXP', 'regex magic survives a copy through an @_ proxy');

done_testing;
