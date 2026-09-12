use strict;
use warnings;
use Test::More;

sub plain_leaf {
    my ($left, $right) = @_;
    return $left + $right;
}

sub regex_leaf {
    'inner' =~ /(inn)(er)/;
    return "$1:$2";
}

'outer' =~ /(out)(er)/;
is(plain_leaf(20, 22), 42, 'regex-free leaf returns its ordinary value');
is("$1:$2", 'out:er', 'regex-free leaf leaves caller captures intact');

is(regex_leaf(), 'inn:er', 'regex-using leaf sees its own captures');
is("$1:$2", 'out:er', 'regex-using leaf restores caller captures');

done_testing;
