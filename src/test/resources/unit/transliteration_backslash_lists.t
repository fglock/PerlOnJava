use strict;
use warnings;
use Test::More;

my $backslash = chr(92);
my $value = 'a' . ($backslash x 2) . '/' . ($backslash x 2) . 'b';
$value =~ tr#\\/#x#s;
is($value, 'axb', 'search list keeps the escaped backslash before the slash');

my $path = 'a' . $backslash . '/b';
$path =~ tr#\\/#\\#s;
is($path, 'a' . $backslash . 'b',
    'replacement list keeps a literal backslash when translating a slash');

done_testing();
