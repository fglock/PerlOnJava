use strict;
use warnings;
use Test::More;

sub caller_filename {
    return (caller(0))[1];
}

chdir 'src/test/resources/unit/issue_1548_caller_filename'
    or die "chdir failed: $!";
@INC = ('.');
require 't004_l2.pm';

is(CallerSource::call(), 't004_l2.pm',
    'caller reports an @INC filename without a synthetic ./ prefix');

done_testing;
