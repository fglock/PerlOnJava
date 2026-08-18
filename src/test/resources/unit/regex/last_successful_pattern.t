use strict;
use warnings;
use Test::More tests => 25;

my ($str, $pat);
$str = 'ABCD';
$str =~ /(D)/;
is("$1", 'D', '$1 is D');
$pat = "${^LAST_SUCCESSFUL_PATTERN}";
is($pat, '(?^:(D))', 'outer last successful pattern');
{
    if ($str =~ /BX/ || $str =~ /(BC)/) {
        is("$1", 'BC', '$1 is BC in inner scope');
        $pat = "${^LAST_SUCCESSFUL_PATTERN}";
        ok($str =~ s//ZZ/, 'empty pattern reuses inner pattern');
        is($str, 'AZZD', 'empty substitution used inner pattern');
    }
}
is("$1", 'D', '$1 restores after inner scope');
is($pat, '(?^:(BC))', 'captured inner pattern string');
ok($str =~ s//Q/, 'outer empty pattern substitution succeeds');
is($str, 'AZZQ', 'outer empty pattern reuses outer pattern');
$pat = "${^LAST_SUCCESSFUL_PATTERN}";
is($pat, '(?^:(D))', 'outer last pattern restores');

$str = 'ABCD';
{
    if ($str =~ /BX/ || $str =~ /(BC)/) {
        is("$1", 'BC', '$1 is BC for explicit special pattern');
        $pat = "${^LAST_SUCCESSFUL_PATTERN}";
        ok($str =~ s/${^LAST_SUCCESSFUL_PATTERN}/ZZ/,
            'special variable matches as a pattern');
        is($str, 'AZZD', 'special variable substitution used inner pattern');
    }
}
is("$1", 'D', '$1 restores after explicit special pattern scope');
is($pat, '(?^:(BC))', 'explicit inner pattern string');
is($str, 'AZZD', 'explicit special pattern matches like empty pattern');
ok($str =~ s/${^LAST_SUCCESSFUL_PATTERN}/Q/,
    'restored special pattern substitution succeeds');
is($str, 'AZZQ', 'restored special pattern used outer pattern');
ok($str =~ /ZQ/, 'new pattern matches');
$pat = "${^LAST_SUCCESSFUL_PATTERN}";
is($pat, '(?^:ZQ)', 'last successful pattern changes');

$str = 'foobarfoo';
ok($str =~ s/foo//, 'matched foo');
my $copy = ${^LAST_SUCCESSFUL_PATTERN};
ok(defined($copy), 'copied pattern is defined');
ok($str =~ s/bar//, 'matched bar');
ok($str =~ s/$copy/PQR/, 'copied regex object remains usable');
is($str, 'PQR', 'copied regex produces final value');
