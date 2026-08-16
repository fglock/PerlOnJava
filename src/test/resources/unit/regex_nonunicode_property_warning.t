use strict;
use warnings;

print "1..2\n";

my $warning = '';
local $SIG{'__WARN__'} = sub { $warning = "@_" };
my $line = __LINE__ + 1;
my $matched = chr(0x110000) =~ /\p{Unassigned}/;

print $warning =~ /Matched non-Unicode code point 0x110000 against Unicode property; may not be portable.*line $line\b/
    ? "ok 1 - Unicode property warning names the match-use line\n"
    : "not ok 1 - Unicode property warning names the match-use line: $warning\n";

{
    no warnings 'non_unicode';
    my $suppressed = '';
    local $SIG{'__WARN__'} = sub { $suppressed = "@_" };
    my $matched = chr(0x110000) =~ /\p{Unassigned}/;
    print $suppressed eq ''
        ? "ok 2 - lexical non_unicode suppression is respected\n"
        : "not ok 2 - lexical non_unicode suppression is respected: $suppressed\n";
}
