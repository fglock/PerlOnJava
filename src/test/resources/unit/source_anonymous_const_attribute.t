use strict;
use warnings;
use attributes ();

my $test = 0;
sub check {
    my ($pass, $name) = @_;
    ++$test;
    print(($pass ? 'ok' : 'not ok'), " $test - $name\n");
}

print "1..4\n";

{
    package AnonymousConstAttribute;
    sub MODIFY_CODE_ATTRIBUTES {
        attributes->import(shift, shift, lc shift) if $_[2];
        return ();
    }
}

my $warning = '';
local $SIG{__WARN__} = sub { $warning .= shift };
$_ = 32487;
my $sub = eval 'package AnonymousConstAttribute; +sub : Const { $_ }';
check($@ eq '', 'custom const attribute compiles');
check($warning eq '', 'const on pending anonymous definition does not warn');
check(ref($sub) eq 'CODE', 'custom const attribute returns a coderef');
undef $_;
check(&$sub == 32487, 'deferred const value is frozen after definition');
