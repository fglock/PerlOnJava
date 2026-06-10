#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

sub store_glob_hash_value {
    my $fh = ref($_[0]) ? $_[0] : \($_[0]);
    my $href = \%{*$fh};
    $href->{asn_buffer} = $_[1];
}

sub fetch_glob_hash_value {
    my $fh = ref($_[0]) ? $_[0] : \($_[0]);
    my $href = \%{*$fh};
    return $href->{asn_buffer};
}

store_glob_hash_value(*X, "buffered");
is fetch_glob_hash_value(*X), "buffered", 'glob HASH slot survives typeglob argument copies';
ok defined(*X{HASH}), 'HASH slot is visible on the canonical glob after writing through an argument copy';

my $g = *Y;
my $href = \%{*$g};
$href->{k} = "shared";
my $canonical = \%{*Y};
is $canonical->{k}, "shared", 'hash deref of a copied named glob shares canonical HASH slot';
ok defined(*$g{HASH}), 'copied glob reports HASH slot after hash deref vivifies it';
ok defined(*Y{HASH}), 'canonical glob reports HASH slot after copied glob vivifies it';

my $devnull = $^O eq 'MSWin32' ? 'NUL' : '/dev/null';
open(IN, '<', $devnull) or die "open $devnull: $!";
store_glob_hash_value(*IN, "open-buffer");
is fetch_glob_hash_value(*IN), "open-buffer", 'open filehandle glob preserves HASH slot through argument copies';
close(IN);

done_testing;
