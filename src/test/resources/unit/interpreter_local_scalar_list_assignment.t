use strict;
use warnings;
use Test::More tests => 4;

sub normalize {
    local($_) = @_;
    return unless defined $_;
    s/^([+-]?)0*(\d+)$/$1$2/;
    $_;
}

my $large = '+24423545234259259259259259259259259259259259259259';
is(normalize($large), $large, 'local scalar assignment keeps the last list value');
is(length(normalize($large)), 51, 'local scalar assignment preserves all digits');

is(normalize('00042'), '42', 'local scalar assignment handles ordinary strings');
is(normalize(), undef, 'local scalar assignment preserves an undef argument result');
