use strict;
use warnings;

use File::Temp qw(tempfile);
use Storable qw(freeze nfreeze thaw store nstore retrieve);
use Test::More;

my $max_uv = ~0;
my @values = (
    $max_uv ^ ($max_uv >> 1),
    $max_uv ^ 1,
    $max_uv,
);

for my $value (@values) {
    my $reference = \$value;
    for my $roundtrip (
        [freeze  => sub { thaw(freeze($_[0])) }],
        [nfreeze => sub { thaw(nfreeze($_[0])) }],
        [store   => sub {
            my ($fh, $path) = tempfile();
            close $fh or die "close tempfile: $!";
            store($_[0], $path) or die "store failed";
            my $copy = retrieve($path);
            unlink $path or die "unlink tempfile: $!";
            return $copy;
        }],
        [nstore  => sub {
            my ($fh, $path) = tempfile();
            close $fh or die "close tempfile: $!";
            nstore($_[0], $path) or die "nstore failed";
            my $copy = retrieve($path);
            unlink $path or die "unlink tempfile: $!";
            return $copy;
        }],
    ) {
        my ($name, $operation) = @$roundtrip;
        my $copy = $operation->($reference);
        is($$copy <=> 0, $value <=> 0, "$name preserves the sign of $value");
    }
}

done_testing();
