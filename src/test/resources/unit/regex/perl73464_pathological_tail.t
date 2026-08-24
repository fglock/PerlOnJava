use strict;
use warnings;
use Test::More tests => 4;

my $size = $ENV{PERL73464_SIZE} || 24;
my $repeats = $ENV{PERL73464_REPEATS} || 1;
my $prefix = 'a' x $size . 'b' x $size . 'c' x $size;

my $miss;
$miss = $prefix =~ /.*a.*b.*c.*[de]/ for 1 .. $repeats;
ok(!$miss,
    'pathological wildcard chain fails when the required tail is absent');
ok("${prefix}d" =~ /.*a.*b.*c.*[de]/,
    'pathological wildcard chain matches a final d');
ok("${prefix}e" =~ /.*a.*b.*c.*[de]/,
    'pathological wildcard chain matches a final e');
ok("x${prefix}dy" =~ /.*a.*b.*c.*[de]/,
    'unanchored wildcard chain retains surrounding-text semantics');
