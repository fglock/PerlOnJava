use strict;
use warnings;
use utf8;
use Test::More;

my $high = chr(0xD800);
my $high_private = chr(0xDB80);
my $low = chr(0xDC00);

ok($high =~ /\A\p{General_Category=Surrogate}\z/u,
    'General_Category=Surrogate matches U+D800');
ok($high_private =~ /\A\p{gc=Cs}\z/u,
    'gc=Cs matches U+DB80');
ok($low =~ /\A\p{Cs}\z/u, 'Cs matches U+DC00');
ok('A' !~ /\p{Cs}/u, 'Cs excludes an ordinary scalar');

ok($high !~ /\A\P{Cs}\z/u, 'complemented Cs excludes U+D800');
ok($low !~ /\A\P{General_Category=Surrogate}\z/u,
    'complemented long category excludes U+DC00');
ok('A' =~ /\A\P{Cs}\z/u, 'complemented Cs includes an ordinary scalar');
ok($high !~ /\P{Cs}/u,
    'unanchored complemented property cannot match inside a surrogate marker');
ok($high !~ /\p{^Cs}/u,
    'unanchored caret complement cannot match inside a surrogate marker');

ok($high =~ /\A\p{Block=High_Surrogates}\z/u,
    'High_Surrogates matches U+D800');
ok($high !~ /\A\p{Block=High_Private_Use_Surrogates}\z/u,
    'High_Private_Use_Surrogates excludes U+D800');
ok($high_private =~ /\A\p{Block=High_Private_Use_Surrogates}\z/u,
    'High_Private_Use_Surrogates matches U+DB80');
ok($low =~ /\A\p{Block=Low_Surrogates}\z/u,
    'Low_Surrogates matches U+DC00');
ok($low !~ /\A\p{Block=High_Surrogates}\z/u,
    'High_Surrogates excludes U+DC00');

ok($high =~ /\A[\p{Block=High_Surrogates}]\z/u,
    'positive class property consumes one surrogate scalar');
ok($high !~ /\A[^\p{Block=High_Surrogates}]\z/u,
    'negated class excludes a member surrogate scalar');
ok($low =~ /\A[^\p{Block=High_Surrogates}]\z/u,
    'negated class includes a different surrogate scalar');
ok('A' =~ /\A[^\p{Block=High_Surrogates}]\z/u,
    'negated class includes an ordinary scalar');

my ($capture) = $high_private =~ /\A(\p{Cs})\z/u;
ok(defined $capture, 'surrogate property capture succeeds');
is(ord($capture), 0xDB80, 'capture preserves the surrogate scalar value');
is(length($capture), 1, 'capture remains one Perl character');

my $substitution = $high . 'A' . $low;
is($substitution =~ s/\p{Block=High_Surrogates}/H/gu, 1,
    'substitution replaces only the selected surrogate block');
is($substitution, 'HA' . $low,
    'substitution preserves the unselected surrogate scalar');

my $sequence = $high . 'A' . $high_private . $low;
my @surrogates = $sequence =~ /(\p{Cs})/gu;
is(scalar @surrogates, 3, 'global property match finds every surrogate scalar');
is_deeply([map { ord } @surrogates], [0xD800, 0xDB80, 0xDC00],
    'global captures preserve surrogate order and values');
is(pos($sequence), undef, 'exhausted global match clears pos');

my @blocks = $sequence =~ /(\p{Block=High_Surrogates}
                            |\p{Block=High_Private_Use_Surrogates}
                            |\p{Block=Low_Surrogates})/gux;
is_deeply([map { ord } @blocks], [0xD800, 0xDB80, 0xDC00],
    'three block properties partition adjacent surrogate scalars');

done_testing;
