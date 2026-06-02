use Test::More tests => 2;

local $ENV{JPERL_UNIMPLEMENTED} = 'warn';
my $matched = eval q{"aaaaaaaaa" =~ /^(a(?(1)\1)){4}$/};

is($@, '', 'unsupported conditional inside a repeated group downgrades under warn');
ok(!$matched, 'unsupported conditional placeholder does not match');
