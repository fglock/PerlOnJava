use strict;
use Test::More;

my $replacement = "Terca";
substr($replacement, 3, 1) = chr(0xe7);
utf8::upgrade($replacement);

ok(utf8::is_utf8($replacement), 'test setup has a UTF-8 flagged Latin-1 replacement');

my $fmt = "%A";
$fmt =~ s/%A/$replacement/e;
is($fmt, $replacement, 's///e inserts the UTF-8 replacement text');
ok(utf8::is_utf8($fmt), 's///e upgrades a byte target when replacement is UTF-8 flagged');

my $source = "%A";
my $result = $source =~ s/%A/$replacement/er;
is($result, $replacement, 's///er inserts the UTF-8 replacement text');
ok(utf8::is_utf8($result), 's///er returns a UTF-8 flagged result');
ok(!utf8::is_utf8($source), 's///er leaves the original byte target unchanged');

done_testing();
