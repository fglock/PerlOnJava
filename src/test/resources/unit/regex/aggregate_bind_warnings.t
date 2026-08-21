use strict;
use warnings;
use Test::More;

sub compile_probe {
    my ($source) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $compiled = eval $source;
    return ($compiled, $@, join('', @warnings));
}

my ($compiled, $error, $warning) = compile_probe(
    q{use warnings 'misc'; sub { my @a =~ // }});
ok(defined $compiled, 'array-bound match compiles');
like($warning,
    qr/^Applying pattern match \(m\/\/\) to \@a will act on scalar\(\@a\) at \(eval \d+\) line 1\.\n$/,
    'declaration-bound array match warns during compilation');

(undef, undef, $warning) = compile_probe(
    q{use warnings 'misc'; sub { my @a; @a !~ /x/ }});
like($warning,
    qr/^Applying pattern match \(m\/\/\) to \@a will act on scalar\(\@a\)/,
    'negative match uses pattern-match warning wording');

(undef, $error, $warning) = compile_probe(
    q{use warnings 'misc'; sub { my @items; @items =~ s/x/y/ }});
like($warning,
    qr/^Applying substitution \(s\/\/\/\) to \@items will act on scalar\(\@items\) at \(eval \d+\) line 1\.\n$/,
    'array substitution uses substitution warning wording');

(undef, $error, $warning) = compile_probe(
    q{use warnings 'misc'; sub { my @letters; @letters =~ tr/x/y/ }});
like($warning,
    qr/^Applying transliteration \(tr\/\/\/\) to \@letters will act on scalar\(\@letters\)/,
    'array transliteration uses transliteration warning wording');

(undef, undef, $warning) = compile_probe(
    q{use warnings 'misc'; sub { my %seen; %seen =~ /x/ }});
like($warning,
    qr/^Applying pattern match \(m\/\/\) to %seen will act on scalar\(%seen\)/,
    'hash match uses the named aggregate');

(undef, undef, $warning) = compile_probe(
    q{use warnings 'misc'; sub { my $ref = []; @$ref =~ /x/ }});
like($warning,
    qr/^Applying pattern match \(m\/\/\) to \@array will act on scalar\(\@array\)/,
    'array dereference uses Perl generic display name');

(undef, $error, $warning) = compile_probe(
    q{use warnings 'misc'; sub { my $ref = {}; %$ref =~ s/x/y/ }});
like($warning,
    qr/^Applying substitution \(s\/\/\/\) to %hash will act on scalar\(%hash\)/,
    'hash dereference uses Perl generic display name');

(undef, undef, $warning) = compile_probe(
    q{use warnings; no warnings 'misc'; sub { my @a; @a =~ /x/ }});
is($warning, '', 'no warnings misc suppresses aggregate binding warning');

(undef, undef, $warning) = compile_probe(
    q{use warnings; no warnings 'regexp'; sub { my @a; @a =~ /x/ }});
like($warning, qr/^Applying pattern match/,
    'regexp suppression does not suppress misc binding warning');

(undef, undef, $warning) = compile_probe(
    q{use warnings 'misc'; sub { my @a; @a =~ qr/x/ }});
my @qr_warnings = $warning =~ /^Applying pattern match/mg;
is(scalar(@qr_warnings), 2,
    'aggregate bound to qr emits both Perl compile-time warnings');
like($warning,
    qr/\A(?:Applying pattern match \(m\/\/\) to \@a will act on scalar\(\@a\) at \(eval \d+\) line 1\.\n){2}\z/,
    'both qr aggregate warnings retain exact text and location');

($compiled, $error, $warning) = compile_probe(
    q{use warnings FATAL => 'misc'; sub { my @a; @a =~ /x/ }});
ok(!defined $compiled, 'fatal misc rejects aggregate-bound match');
like($error,
    qr/^Applying pattern match \(m\/\/\) to \@a will act on scalar\(\@a\) at \(eval \d+\) line 1\.\n$/,
    'fatal misc promotes the warning into the eval error');
is($warning, '', 'fatal misc does not also dispatch a warning');

(undef, undef, $warning) = compile_probe(
    q{use warnings 'misc'; sub { my $scalar; $scalar =~ /x/ }});
is($warning, '', 'scalar match is a warning-free control');

(undef, undef, $warning) = compile_probe(
    q{use warnings 'misc'; sub { my $scalar; $scalar =~ s/x/y/ }});
is($warning, '', 'scalar substitution is a warning-free control');

(undef, undef, $warning) = compile_probe(
    q{use warnings 'misc'; sub { my @a; scalar(@a) =~ /x/ }});
is($warning, '', 'explicit aggregate scalarization is warning-free');

done_testing;
