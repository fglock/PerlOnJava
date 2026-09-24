use strict;
use warnings;
use Test::More tests => 7;

my $target = 'ordinary_sub';
my $value = 41;

sub ordinary_sub : lvalue { $value }
sub strict_lvalue : lvalue {
    use strict 'refs';
    &$target;
}
sub symbolic_lvalue : lvalue {
    no strict 'refs';
    &$target;
}

sub strict_ordinary {
    use strict 'refs';
    &$target;
}

sub strict_numeric {
    use strict 'refs';
    my $numeric = 1;
    &$numeric;
}

my $ok = eval { strict_lvalue(); 1 };
ok(!$ok, 'strict lvalue sub rejects a string code reference');
like($@, qr/Can't use string \("ordinary_sub"\) as a subroutine ref while "strict refs" in use/,
    'strict lvalue sub reports the strict refs diagnostic');
is(symbolic_lvalue(), 41, 'no strict refs still resolves a symbolic code reference');

$ok = eval { strict_ordinary(); 1 };
ok(!$ok, 'ordinary strict sub rejects a string code reference');
like($@, qr/Can't use string \("ordinary_sub"\) as a subroutine ref while "strict refs" in use/,
    'ordinary strict sub reports the strict refs diagnostic');

$ok = eval { strict_numeric(); 1 };
ok(!$ok, 'ordinary strict sub rejects a numeric code reference');
like($@, qr/Can't use string \("1"\) as a subroutine ref while "strict refs" in use/,
    'ordinary strict sub stringifies numeric code references in its diagnostic');
