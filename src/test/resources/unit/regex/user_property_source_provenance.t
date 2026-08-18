use strict;
use warnings;
use feature 'unicode_strings';
use Test::More tests => 8;

our @deferred;
BEGIN {
    for my $name (qw(IsP36Overflow IsP36Reverse IsP36NonHex IsP36Death)) {
        push @deferred, qr/\p{$name}/;
    }
}

my @expected = (
    qr/Code point too large .* expansion of main::IsP36Overflow/s,
    qr/Illegal range .* expansion of main::IsP36Reverse/s,
    qr/Can't find Unicode property definition .* expansion of main::IsP36NonHex/s,
    qr/P36 callback death.* expansion of main::IsP36Death/s,
);

my @names = qw(IsP36Overflow IsP36Reverse IsP36NonHex IsP36Death);
for my $index (0 .. $#deferred) {
    undef $@;
    eval { "A" =~ $deferred[$index] };
    like($@, $expected[$index],
            "deferred literal records canonical user-property package provenance");
}

for my $index (0 .. $#names) {
    undef $@;
    my $name = $names[$index];
    eval { "A" =~ /\p{$name}/ };
    like($@, $expected[$index],
            "source literal retains canonical user-property package provenance");
}

sub IsP36Overflow { "0 80000000000000000\n" }
sub IsP36Reverse  { "200 100\n" }
sub IsP36NonHex   { "BEEF CAGED\n" }
sub IsP36Death    { die "P36 callback death\n" }
