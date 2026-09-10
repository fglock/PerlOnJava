use strict;
use warnings;
use Test::More tests => 4;

our $error = 'previous parse error';

my $grammar = qr{
    (?(DEFINE)
        (?<document> (?&statement)+ )
        (?<statement>
              a
            | (?(?{ !defined $error }) b (?!))
        )
    )
    \A (?&document) \z
}x;

ok('a' =~ $grammar,  'one non-empty statement matches after failed condition');
ok('aa' =~ $grammar, 'two non-empty statements match after failed condition');
ok('aaa' =~ $grammar, 'three non-empty statements match after failed condition');
ok('b' !~ $grammar, 'failed condition does not admit its branch');
