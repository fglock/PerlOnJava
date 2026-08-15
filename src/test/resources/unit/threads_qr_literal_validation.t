use strict;
use warnings;
use threads;

print "1..2\n";

my $ok = eval q{
    my $regex = threads->new(sub { qr/[a\Q]]\Ec/ })->join();
    1;
};

print((!defined($ok) ? "ok" : "not ok"),
      " 1 - malformed constant qr fails in the creating runtime\n");
print(($@ =~ /Unmatched \[/ ? "ok" : "not ok"),
      " 2 - parent eval receives the regex compilation diagnostic\n");
