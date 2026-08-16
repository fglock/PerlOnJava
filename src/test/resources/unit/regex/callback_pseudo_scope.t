use strict;
use warnings;
use Test::More tests => 9;

our $value = 1;
my @seen;
ok('abc' =~ /a(?{ local $value = 2 })b(?{
    push @seen, $value;
    local $value = 3;
})c(?{ push @seen, $value })/, 'callbacks match');
is_deeply(\@seen, [2, 3], 'callback locals remain active during forward matching');
is($value, 1, 'callback locals restore after match completion');

my $last_result = eval q{ "a" =~ /(?{ last })a/; 1 };
ok(!defined $last_result, 'last cannot escape a callback pseudo block');
like($@, qr/Can't "last" outside a loop block/, 'last reports a loop-boundary error');

my $next_result = eval q{ "a" =~ /(?{ next })a/; 1 };
ok(!defined $next_result, 'next cannot escape a callback pseudo block');
like($@, qr/Can't "next" outside a loop block/, 'next reports a loop-boundary error');

my $goto_result = eval q{
"a" =~ /(?{ goto FOO })a/;
FOO: 1;
};
ok(!defined $goto_result, 'goto cannot escape a callback pseudo block');
like($@, qr/Can't "goto" out of a pseudo block at \(eval \d+\) line 2/,
    'goto reports the regex source line');
