use strict;
use warnings;
use Test::More tests => 10;

my $surrogate = chr(0xD800);
my $above_unicode = chr(0x110000);

ok($surrogate !~ /\p{ASCII}/,
    'positive native property cannot inspect a surrogate marker payload');
ok($surrogate =~ /\P{ASCII}/,
    'complemented native property accepts a surrogate');
is($&, $surrogate,
    'complemented native property consumes a surrogate as one scalar');

ok($above_unicode !~ /\p{ASCII}/,
    'positive native property cannot inspect an above-Unicode marker payload');
ok($above_unicode =~ /\P{ASCII}/,
    'complemented native property accepts an above-Unicode scalar');
is($&, $above_unicode,
    'complemented native property consumes an above-Unicode value atomically');

my $mixed = "A${surrogate}B${above_unicode}C";
my @non_ascii = $mixed =~ /(\P{ASCII})/g;
is_deeply(\@non_ascii, [$surrogate, $above_unicode],
    'global complemented properties return complete internal scalars');

my $copy = $mixed;
is($copy =~ s/\P{ASCII}/X/g, 2,
    'global substitution counts internal scalars once each');
is($copy, 'AXBXC',
    'global substitution leaves no marker payload behind');

ok("Q${surrogate}R" !~ /\p{ASCII}{2}$/,
    'property quantifier cannot begin inside an internal marker');
