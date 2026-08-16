use strict;
use warnings;

print "1..8\n";

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= "@_" };
    my $warning_line = __LINE__ + 1;
    "a" =~ /(?{ warn "callback warning" })a/;
    print $warning =~ /callback warning at .* line $warning_line\b/
        ? "ok 1 - callback warning uses the match source line\n"
        : "not ok 1 - callback warning uses the match source line ($warning)\n";
}

our $localized = 'outside';
my $nested_failure = qr/(?{
    local $localized = 'nested callback';
    die "nested callback failure\n";
})b/;
'z' =~ /(z)/;
{
    local $^R = 17;
    my $ok = eval {
        'a' =~ /(?{
            local $localized = 'outer callback';
            'b' =~ m{$nested_failure};
        })a/;
        1;
    };
    print !defined($ok) && $@ =~ /nested callback failure/
        ? "ok 2 - nested callback exception crosses the matcher\n"
        : "not ok 2 - nested callback exception crosses the matcher ($@)\n";
    print $localized eq 'outside'
        ? "ok 3 - nested callback locals unwind\n"
        : "not ok 3 - nested callback locals unwind ($localized)\n";
    print $^R == 17
        ? "ok 4 - nested callback restores R\n"
        : "not ok 4 - nested callback restores R ($^R)\n";
    print !defined($1)
        ? "ok 5 - nested callback restores captures\n"
        : "not ok 5 - nested callback restores captures ($1)\n";
}

our $timeout_scope = 'outside';
my $timed_out = eval {
    local $SIG{ALRM} = sub { die "callback alarm\n" };
    alarm 1;
    'a' =~ /(?{
        local $timeout_scope = 'inside';
        select undef, undef, undef, 5;
    })a/;
    alarm 0;
    1;
};
alarm 0;
print !defined($timed_out) && $@ =~ /callback alarm/
    ? "ok 6 - alarm interrupts a running callback\n"
    : "not ok 6 - alarm interrupts a running callback ($@)\n";
print $timeout_scope eq 'outside'
    ? "ok 7 - interrupted callback locals unwind\n"
    : "not ok 7 - interrupted callback locals unwind ($timeout_scope)\n";
print 'a' =~ /(?{ 1 })a/
    ? "ok 8 - matching continues after callback interruption\n"
    : "not ok 8 - matching continues after callback interruption\n";
