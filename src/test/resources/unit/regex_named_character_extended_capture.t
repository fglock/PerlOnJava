use strict;
use warnings;
use charnames qw(:full);
use Test::More;

my $leader = '### 02020nM2.01200024      h';
ok($leader =~ /^\N{NUMBER SIGN}{3}\s(\d{5}[cdnpu]M2.0\d{7}\s{6}\w)/xms,
    'named character remains significant under /x');
is($1, '02020nM2.01200024      h',
    'capture numbering and content follow a named character escape');

my $faulty = 'this is not a leader';
ok($faulty !~ /^\N{NUMBER SIGN}{3}\s(\d{5}[cdnpu]M2.0\d{7}\s{6}\w)/xms,
    'named character is not treated as an /x comment');

done_testing;
