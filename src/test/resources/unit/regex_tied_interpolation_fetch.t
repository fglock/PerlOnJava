use strict;
use warnings;

{
    package TiedPattern;
    our $fetches = 0;
    sub TIESCALAR { bless [$_[1]], $_[0] }
    sub FETCH { $fetches++; $_[0][0] }
}

print "1..2\n";

tie my $pattern, 'TiedPattern', 'foo';
$_ = 'foo foo';
/$pattern foo/;
print $TiedPattern::fetches == 1 ? "ok 1\n" : "not ok 1\n";

$TiedPattern::fetches = 0;
s/$pattern foo//;
print $TiedPattern::fetches == 1 ? "ok 2\n" : "not ok 2\n";
