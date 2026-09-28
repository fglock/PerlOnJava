use strict;
use warnings;
use Test::More;

{
    package Local::TiedLexicalShadow;

    sub TIESCALAR { bless {}, shift }
    sub FETCH { 'tied value' }
}

{
    tie my $value, 'Local::TiedLexicalShadow';
    is $value, 'tied value', 'inner lexical remains tied';
}

my $value = {};
ok ref($value) eq 'HASH', 'shadowing lexical receives a fresh scalar cell';

done_testing;
