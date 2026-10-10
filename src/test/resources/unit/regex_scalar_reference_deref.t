use strict;
use warnings;
use Test::More;
use Data::Dump qw(dump);

my $pattern = qr/text|xml$|javascript$/;
my $pattern_ref = \$pattern;

is(ref($$pattern_ref), 'Regexp',
    'dereferencing a scalar reference preserves the regex reference');

my $dump = eval { dump($pattern_ref) };
is($@, '', 'Data::Dump handles a scalar reference to a regex');
like($dump, qr/qr[\/|,:#]text\|xml\$\|javascript\$/,
    'Data::Dump preserves the referenced regex');

done_testing;
