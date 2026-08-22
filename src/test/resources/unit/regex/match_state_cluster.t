use strict;
use warnings;
use Test::More tests => 19;

our $x;
$x = $^R = 67;
'foot' =~ /foo(?{$x = 12; 75})[t]/;
is($^R, 75, '$^R publishes successful code result');
$x = $^R = 67;
'foot' =~ /foo(?{$x = 12; 75})[xy]/;
is($^R, 67, '$^R restores its previous value after a failed match');
is($x, 12, 'ordinary side effect at the failed terminal path survives');
$x = $^R = 67;
'foot' =~ /foo(?{ $^R + 12 })((?{ $x = 12; $^R + 17 })[xy])?/;
is($^R, 79, '$^R tracks the final successful branch result');
is($x, 12, 'optional branch code executes before rollback');

$_ = 'ace';
/.(c)(ba*)?/;
is($#{^CAPTURE}, 0, 'capture high index omits unmatched trailing groups');
is($#+, 2, 'end-offset high index retains declared groups');
is($#-, 1, 'start-offset high index omits unmatched trailing groups');

'     ' =~ /()()()(.)(..)/;
my ($m, $p, $q) = (\$-[5], \$+[5], \${^CAPTURE}[4]);
() = "$$_" for $m, $p, $q;
' ' =~ /()/;
is($$m, undef, 'saved alias into @- follows the current match');
is($$p, undef, 'saved alias into @+ follows the current match');
is($$q, undef, 'saved alias into capture array follows the current match');

my $write_ok = eval { $-[0] = 13; 1 };
ok(!$write_ok, '@- element assignment fails');
like($@, qr/^Modification of a read-only value attempted/, '@- diagnostic');
$write_ok = eval { $+[0] = 13; 1 };
ok(!$write_ok, '@+ element assignment fails');
$write_ok = eval { ${^CAPTURE}[0] = 13; 1 };
ok(!$write_ok, 'capture element assignment fails');

ok('a1b' =~ ('xyz' =~ /y/), 'true match result is pattern 1');
is($`, 'a', 'prematch after true-result pattern');
ok('a1b' =~ ('xyz' =~ /t/), 'false match result reuses prior pattern');
is($`, 'a', 'prematch after false-result pattern');
