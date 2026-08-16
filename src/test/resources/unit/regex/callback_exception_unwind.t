use strict;
use warnings;

our $localized = 'outer';
'z' =~ /(z)/;

print "1..4\n";
{
    local $^R = 9;
    my $ok = eval {
        'a' =~ /(a)(?{
            local $localized = 'inside';
            die "callback failure\n";
        })/;
        1;
    };
    print !defined($ok) && $@ =~ /callback failure/ ? "ok 1\n" : "not ok 1\n";
    print $localized eq 'outer' ? "ok 2\n" : "not ok 2\n";
    print $^R == 9 ? "ok 3\n" : "not ok 3\n";
    print !defined($1) ? "ok 4\n" : "not ok 4\n";
}
