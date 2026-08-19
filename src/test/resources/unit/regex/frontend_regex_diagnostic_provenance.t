use strict;
use warnings;
use Test::More;

ok('abbbbc' =~ /\N{ 3 , 4 }/, 'spaced numeric braces remain a quantifier');
is($&, 'abbb', 'spaced non-newline quantifier keeps its match');

my @literal_warnings;
{
    local $SIG{__WARN__} = sub { push @literal_warnings, join '', @_ };
    eval q{qr/[A\EB]c/};
}
like(join('', @literal_warnings), qr/^Useless use of \\E/,
    'literal unpaired E has its compile-time warning');

my $interpolated = '\E';
my @interpolated_warnings;
my $compiled;
{
    local $SIG{__WARN__} = sub { push @interpolated_warnings, join '', @_ };
    $compiled = qr/[A${interpolated}B]/;
}
unlike(join('', @interpolated_warnings), qr/^Useless use of \\E/,
    'interpolated E does not gain the literal-only useless warning');
ok('E' =~ $compiled, 'interpolated E remains a literal character');

eval q{qr/abc\N{def}/};
like($@, qr/^Unknown charname 'def'/,
    'literal unknown name remains fatal with its spelling');

done_testing;
