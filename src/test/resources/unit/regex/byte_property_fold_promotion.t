use strict;
use warnings;
use Test::More;

my $subject = "\xC0a";
utf8::downgrade($subject, 1);

ok($subject =~ /\xE0\pL/i,
    'literal byte followed by a property promotes case folding');
is($&, $subject,
    'literal property fold captures the complete byte subject');

my $literal = "\xE0";
utf8::downgrade($literal, 1);
my $pattern = qr/$literal\pL/i;
ok($subject =~ $pattern,
    'interpolated byte followed by a property promotes case folding');
is($&, $subject,
    'interpolated property fold captures the complete byte subject');

done_testing;
