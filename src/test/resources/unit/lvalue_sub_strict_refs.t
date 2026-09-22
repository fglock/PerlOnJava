use strict;
use warnings;
use Test::More tests => 3;

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

my $ok = eval { strict_lvalue(); 1 };
ok(!$ok, 'strict lvalue sub rejects a string code reference');
like($@, qr/Can't use string \("ordinary_sub"\) as a subroutine ref while "strict refs" in use/,
    'strict lvalue sub reports the strict refs diagnostic');
is(symbolic_lvalue(), 41, 'no strict refs still resolves a symbolic code reference');
