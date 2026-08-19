use strict;
use warnings;
use Test::More;

# perl5_t/t/re/re_tests row 483 records the old capture-retention result as
# pre-5.37.10 behavior. The development host is 5.34; PerlOnJava targets 5.44.
plan skip_all => 'requires Perl 5.37.10 final-iteration capture semantics'
    if $] < 5.037010;

sub captures {
    return map { defined($_) ? $_ : '' } @_;
}

ok('foobar' =~ /((foo)|(bar))*/, 'alternating repeated capture matches');
is_deeply([captures($1, $2, $3)], [qw(bar), '', 'bar'],
    'only captures from the final successful iteration remain');

ok('foobar' =~ /(?:(f)(o)(o)|(b)(a)(r))*/, 'disjoint repeated alternatives match');
is_deeply([captures($1, $2, $3, $4, $5, $6)], ['', '', '', qw(b a r)],
    'captures from the previous alternative are cleared');

ok('aba' =~ /^(a(b)?)+$/, 'optional capture in a repeat matches');
is_deeply([captures($1, $2)], ['a', ''],
    'optional capture omitted by the final iteration is cleared');

ok('ace' =~ /(([ab]+)|([cd]+)|([ef]+))+/, 'multi-alternative repeat matches');
is_deeply([captures($1, $2, $3, $4)], ['e', '', '', 'e'],
    'all inactive alternatives from prior iterations are cleared');

done_testing;
