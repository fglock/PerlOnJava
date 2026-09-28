use strict;
use warnings;
use Test::More;

my $value = eval q{ 'x' =~ /x/r; 1 };
ok(!defined $value, 'match /r modifier is rejected');
like($@, qr/Unknown regexp modifier "\/r"/, 'match /r reports the modifier');

$value = eval q{ 'x' =~ /x/e; 1 };
ok(!defined $value, 'match /e modifier is rejected');
like($@, qr/Unknown regexp modifier "\/e"/, 'match /e reports the modifier');

$_ = 'b';
is(s//x/r, 'xb', 'substitution /r remains valid');

$_ = 'b';
is(s/(.)/uc($1)/er, 'B', 'substitution /e remains valid');

done_testing;
