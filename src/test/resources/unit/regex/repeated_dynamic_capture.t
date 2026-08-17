use strict;
use warnings;
use Test::More tests => 5;
use re 'eval';

our @seen;
my $matched = '123' =~ /^(\d)(((??{
    push @seen, defined $^N ? $^N : 'undef';
    1 + $^N;
})))+$/;
ok($matched, 'repeated dynamic pattern matches');
is(join(',', @seen), '1,2,3', 'each dynamic program sees the preceding capture');
is($1, '1', 'first capture is preserved');
is($2, '3', 'repeated enclosing capture publishes its final value');
is($3, '3', 'nested repeated capture publishes its final value');
