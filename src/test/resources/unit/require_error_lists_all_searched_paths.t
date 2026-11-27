use strict;
use warnings;
use Test::More tests => 1;

{
    local @INC = ('../lib');
    eval { require 'Issue2043/DefinitelyMissing.pm' };

    my @searched_paths = @INC;
    my $last_searched_path = $searched_paths[-1];
    like($@, qr/\Q$last_searched_path\E/,
        'a missing-module diagnostic includes the last path searched in @INC');
}
