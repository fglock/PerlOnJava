use Test::More tests => 2;

*mysubstr = \&CORE::substr;
my $value = 'abc';
&mysubstr($value, 1, 1) = 'long';
is($value, 'alongc', 'CORE::substr coderef is an lvalue');

my $substr = \&CORE::substr;
$value = 'xyz';
$substr->($value, 1, 1) = 'wide';
is($value, 'xwidez', 'CORE::substr coderef lvalue works with arrow calls');
