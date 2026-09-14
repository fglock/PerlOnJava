use v5.40;
use Test::More;

my $ok = eval q{ "\L\L"; 1 };
ok !$ok, 'a repeated lowercase range modifier is a syntax error';
like $@, qr/syntax error/, 'the repeated lowercase modifier reports a syntax error';

$ok = eval q{ "\U\U"; 1 };
ok !$ok, 'a repeated uppercase range modifier is a syntax error';
like $@, qr/syntax error/, 'the repeated uppercase modifier reports a syntax error';

done_testing;
