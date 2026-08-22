use strict;
use warnings;
use POSIX qw(LC_CTYPE setlocale);
use Test::More tests => 20;

my $saved = setlocale(LC_CTYPE);
ok(defined setlocale(LC_CTYPE, 'C'), 'select C locale');

sub run_case {
    my ($name, $subject, $pattern, $want_match, $want_warnings) = @_;
    my @warnings;
    my $matched;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        $matched = $subject =~ $pattern;
    }
    is(0 + !!$matched, $want_match, "$name match result");
    is(scalar @warnings, scalar @$want_warnings, "$name warning count");
    for my $index (0 .. $#$want_warnings) {
        $warnings[$index] =~ s/ at \Q$0\E line \d+\.\n?\z//;
        is($warnings[$index], $want_warnings->[$index],
            "$name warning " . ($index + 1));
    }
}

run_case('wide pattern versus byte', "\xFE", qr/(?il)\x{100}/, 0, []);
run_case('wide pattern versus ASCII', 'A', qr/(?il)\x{100}/, 0, []);
run_case('wide pattern versus itself', "\x{100}", qr/(?il)\x{100}/, 1,
    [ ('Wide character (U+100) in pattern match (m//)') x 2 ]);
run_case('wide simple-fold pair', "\x{101}", qr/(?il)\x{100}/, 1,
    [ ('Wide character (U+101) in pattern match (m//)') x 2 ]);
run_case('byte pattern versus wide', "\x{100}", qr/(?il)\x{FE}/, 0,
    [ 'Wide character (U+100) in pattern match (m//)' ]);

my @suppressed;
my $suppressed_match;
{
    no warnings 'locale';
    local $SIG{__WARN__} = sub { push @suppressed, @_ };
    $suppressed_match = "\x{101}" =~ /(?il)\x{100}/;
}
ok($suppressed_match, 'disabled locale warnings retain wide simple folding');
is(scalar @suppressed, 0, 'locale category suppresses wide fold warnings');

my $fatal = eval q{
    use warnings FATAL => 'locale';
    "\x{101}" =~ /(?il)\x{100}/;
    1;
};
ok(!$fatal, 'fatal locale warning aborts the wide comparison');
$@ =~ s/ at \(eval \d+\) line \d+\.\n?\z//;
is($@, 'Wide character (U+101) in pattern match (m//)',
    'fatal locale warning preserves Perl shape');

setlocale(LC_CTYPE, $saved) if defined $saved;
