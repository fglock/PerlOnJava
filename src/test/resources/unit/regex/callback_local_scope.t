use strict;
use warnings;

print "1..4\n";

our $value = 1;
our @seen;
'ab' =~ /a(?{
    push @seen, $value;
    local $value = $value + 1;
})b(?{
    push @seen, $value;
})/;
print join(',', @seen) eq '1,2'
    ? "ok 1 - successful callback local is visible to later callbacks\n"
    : "not ok 1 - successful callback local is visible to later callbacks\n";
print $value == 1
    ? "ok 2 - successful match unwinds callback locals\n"
    : "not ok 2 - successful match unwinds callback locals\n";

$value = 1;
@seen = ();
'ac' =~ /(?:a(?{ local $value = 2 })b|a(?{ push @seen, $value })c)/;
print join(',', @seen) eq '1'
    ? "ok 3 - abandoned callback locals do not leak to another alternative\n"
    : "not ok 3 - abandoned callback locals do not leak to another alternative\n";
print $value == 1
    ? "ok 4 - backtracking unwinds callback locals\n"
    : "not ok 4 - backtracking unwinds callback locals\n";
