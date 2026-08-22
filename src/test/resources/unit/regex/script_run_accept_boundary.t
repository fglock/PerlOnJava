use strict;
use warnings;
use utf8;
use Test::More;

no warnings 'experimental::script_run';

my $mixed = "A\x{0430}";

ok($mixed =~ /\A(*script_run:\w+(*ACCEPT))z\z/,
    'uncaptured ACCEPT bypasses script-run validation');
is($&, $mixed, 'uncaptured ACCEPT fixes the whole mixed-script endpoint');

ok($mixed =~ /\A(*script_run:(\w+))(*ACCEPT)z\z/,
    'captured script run validates before the following ACCEPT');
is($1, 'A', 'validated script run backtracks to the single-script prefix');
is($&, 'A', 'following ACCEPT retains the validated prefix endpoint');

ok('AB' =~ /\A(*script_run:(\w+))(*ACCEPT)z\z/,
    'captured script run consumes a complete same-script span');
is($1, 'AB', 'captured ACCEPT closes the validating capture');
is($&, 'AB', 'captured ACCEPT retains the accepted endpoint');

done_testing;
