use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 2;

my ($seed, $path) = tempfile(UNLINK => 0);
close $seed or die "close seed: $!";

{
    open my $out, '>', $path or die "open write: $!";
    binmode $out;
    print {$out} "scope-exit-payload" or die "write: $!";
    # Deliberately do not close or undef $out.
}

open my $in, '<', $path or die "open read: $!";
binmode $in;
local $/;
my $got = <$in> // '';
close $in or die "close read: $!";
unlink $path or die "unlink $path: $!";

diag('bytes=' . length($got));
is(length($got), 18, 'scope-exit file contents are readable');
is($got, 'scope-exit-payload', 'lexical filehandle flushes at scope exit');
