use strict;
use warnings;
use threads;

print "1..4\n";

print(("aaa" !~ /a*(*FAIL)/ ? "ok" : "not ok"),
      " 1 - FAIL forces the direct branch to backtrack and fail\n");
print(("aaa" !~ /a*(*F)/ ? "ok" : "not ok"),
      " 2 - F is the short spelling of FAIL\n");

my $long = threads->create(sub { qr/a*(*FAIL)/ })->join;
my $short = threads->create(sub { qr/a*(*F)/ })->join;
print(("aaa" !~ $long ? "ok" : "not ok"),
      " 3 - a FAIL regex retains its semantics across snapshot join\n");
print(("aaa" !~ $short ? "ok" : "not ok"),
      " 4 - a short FAIL regex retains its semantics across snapshot join\n");
