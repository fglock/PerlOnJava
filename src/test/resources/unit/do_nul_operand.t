use Test::More tests => 2;

my $value = eval "do\0000000";

is $@, '', 'NUL after do does not create a syntax error';
ok !defined $value, 'NUL-terminated do-file operand returns undef';
