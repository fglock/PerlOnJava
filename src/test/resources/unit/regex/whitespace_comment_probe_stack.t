use Test::More tests => 3;

my $value = join ' ', map { q(') . "luck$_" . q(') } reverse 1 .. 2000;
my $line = 'test_single_many ' . $value;

ok($line !~ /(\s*\/\*.*\*\/\s*)/, 'long line without C comment does not overflow comment probe');
ok($line !~ /^\s*([^=]+?)\s*=\s*<<\s*(.+?)\s*$/, 'long line without required delimiter fails before Java backtracking overflow');

my $commented = "name /* remove this */ value";
$commented =~ s/\s*\/\*.*\*\/\s*//;
is($commented, 'namevalue', 'C comment stripping keeps original match semantics');
