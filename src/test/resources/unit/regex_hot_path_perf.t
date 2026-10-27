use strict;
use warnings;
use Test::More;

# Tests for regex hot-path optimizations:
# - ASCII input encoding fast path (bypasses synchronized cache)
# - Identity array pooling for charToByte/byteToChar mappings
# - Integer-to-string conversion in regex matching context
# - Non-ASCII fallback still works correctly

plan tests => 29;

# --- ASCII string matching (exercises fast-path input encoding) ---

my $str = "hello world 42";
ok($str =~ /hello/, "ASCII match - simple word");
ok($str =~ /42/, "ASCII match - digits");
ok($str =~ /hello\s+world/, "ASCII match - with metachar");
ok($str !~ /xyz/, "ASCII non-match");

# Captures with ASCII strings
if ($str =~ /(\w+)\s+(\w+)\s+(\d+)/) {
    is($1, "hello", "ASCII capture group 1");
    is($2, "world", "ASCII capture group 2");
    is($3, "42", "ASCII capture group 3");
} else {
    fail("ASCII capture match failed") for 1..3;
}

# --- Integer matching (exercises INTEGER toString fast path) ---

my $i = 42;
ok($i =~ /42/, "integer =~ literal match");
ok($i =~ /4/, "integer =~ partial match");
ok($i !~ /99/, "integer =~ non-match");

# Integer in loop (the benchmark pattern)
my $count = 0;
for my $n (0..99) {
    $count++ if $n =~ /42/;
}
is($count, 1, "integer regex in loop finds exactly one match");

# Negative integer
$i = -7;
ok($i =~ /-7/, "negative integer match");
ok($i =~ /\d/, "negative integer digit match");

# Large integer (beyond pool size but still integer type)
$i = 123456789;
ok($i =~ /123456789/, "large integer exact match");
ok($i =~ /456/, "large integer substring match");

# --- Short ASCII strings (within identity array pool, <=64 chars) ---

my $short = "abc";
ok($short =~ /abc/, "short ASCII (3 chars) match");

my $pool_boundary = "a" x 64;
ok($pool_boundary =~ /a{64}/, "pool boundary (64 chars) match");

my $over_pool = "b" x 65;
ok($over_pool =~ /b{65}/, "above pool size (65 chars) match");

# --- Non-ASCII / Unicode (must still work via fallback path) ---

my $unicode = "café";
ok($unicode =~ /caf/, "unicode string ASCII prefix match");
ok($unicode =~ /é/, "unicode string non-ASCII char match");

my $wide = "\x{263A}";  # smiley
ok($wide =~ /\x{263A}/, "wide unicode char match");

my $mixed = "abc\x{00E9}def";  # abc + é + def
if ($mixed =~ /(abc)(\x{00E9})(def)/) {
    is($1, "abc", "mixed unicode capture group 1");
    is($2, "\x{00E9}", "mixed unicode capture group 2");
    is($3, "def", "mixed unicode capture group 3");
} else {
    fail("mixed unicode capture failed") for 1..3;
}

# --- Global match with integer target ---

my @matches;
$i = 121212;
@matches = ("$i" =~ /12/g);
is(scalar @matches, 3, "global match on stringified integer");

# --- Substitution with integer ---

$i = 42;
my $s = "$i";
$s =~ s/4/X/;
is($s, "X2", "substitution on stringified integer");

# --- Empty and single-char edge cases ---

$str = "";
ok($str =~ /^$/, "empty string matches empty pattern");
ok($str !~ /a/, "empty string does not match 'a'");

$str = "x";
ok($str =~ /x/, "single char ASCII match");

