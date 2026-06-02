use Test::More tests => 2;

my $value = q('a' 'b');
$value =~ s/(\'[^\']+?\')/X/g;
is($value, 'X X', 'lazy negated class still stops at each excluded delimiter');

my $dupcount = 2000;
my $many = join ' ', map { q(') . "luck$_" . q(') } reverse 1 .. $dupcount;
my $count = 0;
$many =~ s/(\'[^\']+?\')/"QUOTE" . (++$count) . "QUOTE"/exg;

is($count, $dupcount, 'many quoted strings do not overflow Java regex lazy-loop stack');
