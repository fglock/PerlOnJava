use strict;
use warnings;
use Test::More tests => 3;

our @seen;
my $matched = 'ab' =~ /\A
    (?<recursive>
        (.)
        (?:(?&recursive))?
        (*{
            push @seen,
                (defined $^N ? $^N : 'undef') . ':' .
                (defined $+  ? $+  : 'undef');
        })
    )
\z/x;
my $committed = (defined $1  ? $1  : 'undef') . ':' .
                (defined $^N ? $^N : 'undef') . ':' .
                (defined $+  ? $+  : 'undef');

ok($matched, 'recursive subpattern matches');
is(join(',', @seen), 'b:b,a:a',
    'return from recursion restores caller captures for optimistic callback');
is($committed, 'ab:ab:a', 'committed match publishes outer capture state');
