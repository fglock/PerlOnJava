use strict;
use warnings;
use Test::More tests => 3;

my $plain = qr/(?xi)(?=[MDCLXVI])
    M{0,4}
    (?:C[DM]|D?C{0,4})?
    (?:X[LC]|L?X{0,4})?
    (?:I[VX]|V?I{0,4})?/;
my $keep = qr/((?xi)(?=[MDCLXVI])
    M{0,4}
    (?:C[DM]|D?C{0,4})?
    (?:X[LC]|L?X{0,4})?
    (?:I[VX]|V?I{0,4})?)/;

my @source = qw(I IV VII IX XL XC CD CM MMMMCMXCIX);
my ($plain_ok, $keep_ok) = (0, 0);
for (1 .. 2_000) {
    for my $source (@source) {
        my $subject = join '', split //, $source;
        utf8::upgrade($subject);
        $plain_ok++ if $subject =~ /^$plain/ && $& eq $subject;

        my $lower = lc $subject;
        $keep_ok++ if $lower =~ /^$keep/ && $& eq $lower && $1 eq $lower;
    }
}

is($plain_ok, 18_000,
    'transient upgraded subjects retain complete interpolated-qr matches');
is($keep_ok, 18_000,
    'transient lowercase subjects retain complete captures');
is("\x{100}", "\x{100}",
    'wide subject construction remains intact after cache churn');
