use strict;
use warnings;
use Test::More tests => 3;

BEGIN { undef &Regexp::DESTROY }

my $destroyed = 0;
{
    no warnings 'redefine';
    sub Regexp::DESTROY { $destroyed++ }
}

{
    my $rx = qr//;
}
is($destroyed, 1, 'plain qr object invokes Regexp::DESTROY at scope exit');

{
    my $captured = bless {}, 'RegexDestroyCapture';
    my $rx = qr/(?{ $captured })/;
}
is($destroyed, 2,
    'callback-bearing qr object invokes Regexp::DESTROY at scope exit');

{
    my $source = bless qr/x/, 'RegexDestroySubclass';
    my $rx = qr/$source/i;
    is(ref($rx), 'Regexp', 'new qr wrapper uses the implicit Regexp class');
}
