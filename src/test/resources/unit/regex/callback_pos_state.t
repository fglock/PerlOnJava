use strict;
use warnings;
use Test::More tests => 24;

my $message = 'pos inside (?{ })';
my $str = 'abcde';
our ($foo, $bar);
like($str, qr/b(?{$foo = $_; $bar = pos})c/, $message);
is($foo, $str, $message);
is($bar, 2, $message);
is(pos $str, undef, $message);

undef $foo;
undef $bar;
pos $str = undef;
ok($str =~ /b(?{$foo = $_; $bar = pos})c/g, $message);
is($foo, $str, $message);
is($bar, 2, $message);
is(pos $str, 3, $message);

$_ = $str;
undef $foo;
undef $bar;
like($_, qr/b(?{$foo = $_; $bar = pos})c/, $message);
is($foo, $str, $message);
is($bar, 2, $message);

undef $foo;
undef $bar;
ok(/b(?{$foo = $_; $bar = pos})c/g, $message);
is($foo, $str, $message);
is($bar, 2, $message);
is(pos, 3, $message);

undef $foo;
undef $bar;
pos = undef;
1 while /b(?{$foo = $_; $bar = pos})c/g;
is($foo, $str, $message);
is($bar, 2, $message);
is(pos, undef, $message);

undef $foo;
undef $bar;
$_ = 'abcde|abcde';
ok(s/b(?{$foo = $_; $bar = pos})c/x/g, $message);
is($foo, 'abcde|abcde', $message);
is($bar, 8, $message);
is($_, 'axde|axde', $message);

our @res;
$_ = 'abcde|abcde';
() = /([ace]).(?{push @res, $1,$2})([ce])(?{push @res, $1,$2})/g;
@res = map {defined $_ ? "'$_'" : 'undef'} @res;
is("@res", "'a' undef 'a' 'c' 'e' undef 'a' undef 'a' 'c'", $message);

@res = ();
() = /([ace]).(?{push @res, $`,$&,$'})([ce])(?{push @res, $`,$&,$'})/g;
@res = map {defined $_ ? "'$_'" : 'undef'} @res;
is("@res", "'' 'ab' 'cde|abcde' " .
           "'' 'abc' 'de|abcde' " .
           "'abcd' 'e|' 'abcde' " .
           "'abcde|' 'ab' 'cde' " .
           "'abcde|' 'abc' 'de'", $message);
