use v5.36;
use Test::More tests => 8;

# Bad signature tests using eval
# Test invalid sigil
eval 'sub bad_sigil (&) { }';
like($@, qr/signature parameter must start with '\$', '\@' or '%'/, 'Invalid sigil & detected');

# Test double sigil
eval 'sub double_sigil ($$) { }';
like($@, qr/Illegal character following sigil/, 'Double sigil $ detected');

# Test invalid character after sigil
eval 'sub bad_char ($#) { }';
like($@, qr/'#' not allowed immediately following a sigil in a subroutine signature/, 'Invalid character after sigil detected');

# Test missing comma
eval 'sub missing_comma ($a $b) { }';
like($@, qr/syntax error/, 'Missing comma detected');

## # Test invalid default value syntax
## eval 'sub bad_default ($x =) { }';
## like($@, qr/Optional parameter lacks default expression/, 'Invalid default value syntax detected');

# Test slurpy not at end
eval 'sub slurpy_middle (@rest, $last) { }';
like($@, qr/Slurpy parameter not last/, 'Slurpy parameter not at end detected');

# Test multiple slurpy
eval 'sub multi_slurpy (@a, @b) { }';
like($@, qr/Multiple slurpy parameters not allowed/, 'Multiple slurpy parameters detected');

# Test invalid prototype character
eval 'sub bad_proto (*) { }';
like($@, qr/signature parameter must start with '\$', '\@' or '%'/, 'Invalid prototype character * detected');

