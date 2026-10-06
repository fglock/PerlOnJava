use Test::More tests => 6;

my $vec = \&CORE::vec;
my $value = '';
$vec->($value, 0, 8) = 65;
is($value, 'A', 'CORE::vec coderef assignment updates its source scalar');
is($vec->($value, 0, 8), 65, 'CORE::vec coderef reads the selected bits');

$value = '';
&$vec($value, 1, 8) = 66;
is($value, "\0B", 'CORE::vec coderef assignment works with explicit dereference');

my $untie = \&CORE::untie;
eval { $untie->(1) };
like($@, qr/^Type of arg 1 to &CORE::untie must be reference to one of \[\$\@%\*\]/,
    'CORE::untie coderef validates its target type');

{
    package CoreUntieCoderefTie;
    sub TIESCALAR { bless {}, shift }
    sub FETCH { 1 }
    sub STORE { }
}

tie my $scalar, 'CoreUntieCoderefTie';
ok(defined tied($scalar), 'scalar is tied before CORE::untie coderef call');
ok($untie->(\$scalar), 'CORE::untie coderef unties a scalar');
