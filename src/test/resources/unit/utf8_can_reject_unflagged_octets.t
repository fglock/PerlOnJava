use strict;
use warnings;
use utf8;
use Test::More;

{
    package Utf8MethodLookup;
    sub nèw { bless {}, shift }
}

my $method = "nèw";
utf8::encode($method);
ok(!Utf8MethodLookup->can($method),
    'can does not decode unflagged UTF-8 octets into a method name');

done_testing();
