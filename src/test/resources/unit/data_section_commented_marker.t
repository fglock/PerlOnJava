use strict;
use warnings;
use feature 'switch';
use Test::More;

# __DATA__ in this comment must not create a DATA filehandle.
my $matched = 0;
given (0) {
    when (eof(DATA)) { $matched = 1 }
}
ok $matched, 'commented data marker does not change eof(DATA)';

done_testing;
