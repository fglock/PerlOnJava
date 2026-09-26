use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

given ('xyz') {
    my @values = ('a', do { when (/abc/) { 'matched' } }, 'b');
    is join(',', @values), 'a,b',
        'a false when contributes no list value';

    my $value = do { when (/abc/) { 'matched' } };
    ok !defined($value), 'a false when is undef in scalar context';
}

done_testing;
