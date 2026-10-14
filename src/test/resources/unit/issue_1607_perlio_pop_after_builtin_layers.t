use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More;

my ($seed, $path) = tempfile();
close $seed or die "close seed file: $!";

my @layers = qw(:unix :stdio :perlio :crlf);
plan tests => 2 * scalar @layers;

for my $layer (@layers) {
    my $fh;
    ok(open($fh, "<$layer", $path), "open with $layer layer");
    next unless $fh;

    my $ok = binmode($fh, ':pop');
    ok($ok, "binmode :pop after $layer layer");
    close $fh;
}

unlink $path or die "unlink $path: $!";
