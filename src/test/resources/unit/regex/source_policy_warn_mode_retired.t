use strict;
use warnings;
use Test::More tests => 15;

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };

{
    BEGIN {
        $^H{charnames} = sub {
            $_[0] eq 'BOOM' ? die("name explosion\n") : 'A';
        };
    }

    my $custom_failure = eval q{qr/\N{BOOM}/};
    ok(!defined $custom_failure,
        'custom charname exception remains a fatal compilation failure');
    like($@, qr/^name explosion/,
        'custom charname exception retains the callback diagnostic');
    is(scalar @warnings, 0,
        'custom charname exception is not downgraded to warn-and-never-match');
    @warnings = ();

    my $custom_control = eval q{qr/\N{SAFE}/};
    ok(defined $custom_control, 'successful custom charname still compiles');
    ok('A' =~ $custom_control, 'successful custom charname still matches');
}

my $malformed = eval 'qr/(?{ 1 })(?<name>x/';
ok(!defined $malformed, 'malformed executable literal remains fatal');
like($@, qr/Unmatched \(/,
    'malformed executable literal retains the regex diagnostic');
is(scalar @warnings, 0,
    'compatibility environment does not downgrade literal failure');

my $invalid = eval q{qr{[z-a]}};
ok(!defined $invalid, 'native Joni syntax failure remains fatal');
like($@, qr/Invalid \[\] range/,
    'native Joni syntax failure retains the Perl diagnostic');
is(scalar @warnings, 0,
    'compatibility environment does not downgrade native failure');

my $counter = 0;
my $literal = qr{x(?{ $counter++ })};
ok('x' =~ $literal, 'trusted literal callout still matches');
is($counter, 1, 'trusted literal callout still executes');

my $source = 'x(?{ $counter++ })';
my $runtime = eval { qr/$source/ };
ok(!defined $runtime, 'untrusted runtime executable source remains denied');
like($@, qr/Eval-group not allowed at runtime/,
    'runtime source admission retains the Perl diagnostic');
