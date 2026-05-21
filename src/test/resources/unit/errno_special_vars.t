use strict;
use warnings;
use Test::More;

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };

ok(defined $^E, '$^E is defined at startup');
is(0 + $^E, 0, '$^E defaults to numeric zero');
is("$^E", '', '$^E defaults to an empty string');
is_deeply(\@warnings, [], 'numeric $^E does not warn when errno is clear');

$! = 2;
is(0 + $^E, 2, '$^E reflects numeric $!');
is("$^E", "$!", '$^E reflects string $!');

$^E = 3;
is(0 + $!, 3, 'assigning $^E updates numeric $!');
is("$!", "$^E", 'assigning $^E updates string $!');

$! = 2;
{
    local $^E = 0;
    is(0 + $!, 0, 'local $^E localizes shared numeric errno');
}
is(0 + $!, 2, 'leaving local $^E restores shared numeric errno');

$! = 0;
is(0 + $^E, 0, 'clearing $! clears numeric $^E');
is("$^E", '', 'clearing $! clears string $^E');

done_testing();
