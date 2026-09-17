use Test::More;

BEGIN {
    plan skip_all => 'requires Perl 5.44 class syntax' if $] < 5.044;
}

use v5.44;
use experimental 'class';

my $ok = eval q{
    class GlobalFieldError {
        field $_;
    }
    1;
};

ok(!$ok, 'global $_ cannot be declared as a class field');
like($@, qr/Can't use global \$_ in "field"/, 'reports the class-field diagnostic');
like($@, qr/near "field \$_"/, 'diagnostic includes the complete field declaration');

done_testing;
