use strict;
use warnings;
use charnames ':full';
use Test::More;
no warnings 'experimental::regex_sets';

my $name = 'LATIN CAPITAL LETTER A WITH MACRON AND GRAVE';
my $canonical = 'U+100.300';

my @fatal = (
    [ 'nested extended class',
      qq{qr/(?[[^\\N{$name}]])/} ],
    [ 'strict negated class',
      qq{no warnings 'experimental::re_strict'; use re 'strict'; qr/[^\\N{$name}]/} ],
    [ 'strict range end',
      qq{no warnings 'experimental::re_strict'; use re 'strict'; qr/[\\x03-\\N{$name}]/} ],
    [ 'strict range start',
      qq{no warnings 'experimental::re_strict'; use re 'strict'; qr/[\\N{$name}-\\x{10FFFF}]/} ],
);

for my $case (@fatal) {
    my ($label, $source) = @$case;
    eval $source;
    like($@, qr/^\\N\{\} here is restricted to one character in regex;/,
        "$label is fatal");
    like($@, qr/\\N\{\Q$canonical\E <-- HERE \}/,
        "$label renders the canonical sequence at the marker");
}

eval qq{qr/(?[[^\\N{$name}]])/};
my $nested_rendering = "m/(?[[^\\N{$canonical <-- HERE }]])/";
ok(index($@, $nested_rendering) >= 0,
    'nested extended diagnostic retains the complete pattern suffix');

my $sequence = "\x{100}\x{300}";
my $positive = qr/[\N{LATIN CAPITAL LETTER A WITH MACRON AND GRAVE}]/;
ok($sequence =~ /\A$positive\z/,
    'ordinary positive class still accepts a named sequence');

my $warning = '';
my $negated;
{
    local $SIG{__WARN__} = sub { $warning .= join '', @_ };
    $negated = eval qq{qr/[^\\N{$name}]/};
}
ok(defined $negated, 'ordinary non-strict negated class remains accepted');
like($warning, qr/Using just the first character returned by \\N\{\}/,
    'ordinary non-strict negated class retains its warning');

done_testing;
