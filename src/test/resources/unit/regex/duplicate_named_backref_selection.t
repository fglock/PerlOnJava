use strict;
use warnings;
use Test::More;

my $pattern = qr/\A(?<D>a)(?<D>b)\k<D>\z/;

ok('aba' =~ $pattern,
    'duplicate named backreference uses the oldest participating capture');
ok('abb' !~ $pattern,
    'duplicate named backreference does not retry a newer capture');

my $case_insensitive = qr/\A(?<D>a)(?<D>b)\k<D>\z/i;
ok('aBa' =~ $case_insensitive,
    'case-insensitive duplicate named backreference uses the oldest capture');
ok('aBB' !~ $case_insensitive,
    'case-insensitive duplicate named backreference does not retry a newer capture');

done_testing;
