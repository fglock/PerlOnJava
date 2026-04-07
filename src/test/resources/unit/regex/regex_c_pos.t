use strict;
use Test::More;
use feature 'say';

my $str = "hello hello hello";

# Test where /c makes a difference
pos($str) = 6;  # Position at second "hello"

# Without /c - matches from current position
$str =~ /hello/g;  
ok(!(pos($str) != 11), 'without /c, matched from pos=6, pos=11');

# With /c - also matches from current pos
pos($str) = 6;
$str =~ /hello/gc;
ok(!(pos($str) != 11), 'with /c, matched from pos=6 to pos=11');

# Now test with a failed match to see the difference
pos($str) = 6;
$str =~ /xyz/g;  # Fails and resets pos()
ok(!(defined pos($str)), '/g resets pos() on failure');

pos($str) = 6;
$str =~ /xyz/gc;  # Fails but keeps pos()
ok(!(pos($str) != 6), '/gc preserves pos() on failure');

# Test /gc with zero-length match followed by another match at same position
# This is critical for IO::HTML::find_charset_in and similar /gc-based parsers
{
    my $s = "abc";
    pos($s) = 3;  # at end of string
    $s =~ /\G(=?[^\t\n =]*)/gc;  # zero-length match at end
    is(pos($s), 3, '/gc zero-length match at end preserves pos');

    my $r = ($s =~ /\G(=?[^\t\n =]*)/gc);
    ok(!$r, '/gc second zero-length match at same position fails');
    is(pos($s), 3, '/gc pos preserved after failed zero-length match with /c');
}

# Test /g (without /c) resets pos after zero-length match followed by failure
{
    my $s = "abc";
    pos($s) = 3;
    $s =~ /\G(=?[^\t\n =]*)/g;  # zero-length match at end
    is(pos($s), 3, '/g zero-length match at end preserves pos');

    my $r = ($s =~ /\G(=?[^\t\n =]*)/g);
    ok(!$r, '/g second zero-length match at same position fails');
    ok(!defined pos($s), '/g pos reset after failed zero-length match without /c');
}

done_testing();
