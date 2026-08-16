#!/usr/bin/env perl
use strict;
use warnings;

use Digest::SHA ();

my ($manifest) = @ARGV;
die "Usage: $0 MANIFEST\n" unless defined $manifest && @ARGV == 1;

open my $list, '<', $manifest or die "Cannot open $manifest: $!\n";
my $failed = 0;
while (my $line = <$list>) {
    chomp $line;
    next if $line =~ /^\s*(?:#|$)/;
    my ($expected, $path) = $line =~ /^([0-9a-f]{64})\s{2}(.+)$/
        or die "Malformed manifest line $. in $manifest\n";
    if (!-f $path) {
        warn "Missing pinned test source: $path\n";
        $failed = 1;
        next;
    }
    open my $source, '<', $path or die "Cannot open $path: $!\n";
    binmode $source;
    my $actual = Digest::SHA->new(256)->addfile($source)->hexdigest;
    if ($actual ne $expected) {
        warn "Pinned test source differs: $path\n";
        $failed = 1;
    }
}
close $list or die "Cannot close $manifest: $!\n";
exit($failed ? 1 : 0);
