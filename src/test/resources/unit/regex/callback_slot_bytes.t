use strict;
use warnings;
use Test::More;

my @raw = (
    ["\x{1e}", 'reserved opening byte'],
    ["\x{1e}B0\x{1f}", 'block-slot-shaped bytes'],
    ["\x{1e}C12\x{1f}", 'condition-slot-shaped bytes'],
    ["\x{1e}D9\x{1f}", 'dynamic-slot-shaped bytes'],
    ["\x{1e}\x{1e}", 'doubled reserved opening bytes'],
    ["\x{1e}B\x{1f}", 'slot without an id'],
    ["\x{1e}X0\x{1f}", 'slot with an unknown kind'],
    ["\x{1e}B999", 'slot without a closing byte'],
);

for my $case (@raw) {
    my ($text, $label) = @$case;
    ok($text =~ /^$text$/, "$label round-trips without a callback table");

    my $calls = 0;
    my $pattern = qr/^(?{ ++$calls })$text$/;
    ok($text =~ $pattern, "$label round-trips beside a genuine callback");
    is($calls, 1, "$label cannot manufacture or suppress a callback");
}

my $calls = 0;
my $slot_start = "\x{1e}";
my $slot_tail = "B0\x{1f}";
my $split = qr/^(?{ ++$calls })$slot_start$slot_tail$/;
ok("\x{1e}B0\x{1f}" =~ $split,
    'separate interpolations cannot assemble a trusted slot');
is($calls, 1, 'split slot-shaped bytes execute only the genuine callback');

done_testing;
