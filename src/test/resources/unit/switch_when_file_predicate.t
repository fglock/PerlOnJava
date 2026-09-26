use strict;
use warnings;
use feature 'switch';
use Test::More;

my $directory;
given ('.') {
    when (-d) { $directory = 1 }
}
ok $directory, 'file test when condition is boolean';

done_testing;
