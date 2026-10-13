use strict;
use warnings;
use Test::More;

my $ok = eval q{@eval_strict_missing};
ok(!$ok, 'string eval inherits strict vars');
like($@, qr/Global symbol "\@eval_strict_missing" requires explicit package name/,
    'strict vars diagnostic names the missing package variable');

$ok = eval q{no strict 'vars'; scalar @eval_strict_missing; 1};
ok($ok, 'string eval can disable strict vars locally');
is($@, '', 'no strict vars eval has no compile error');

our $EVAL_STRICT_DECLARED = 'visible';
is(eval q{$EVAL_STRICT_DECLARED}, 'visible',
    'declared package variables remain visible in string eval');

done_testing();
