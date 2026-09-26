use strict;
use warnings;
use Test::More;

ok eof(DATA), 'an empty top-level DATA section is immediately at EOF';

done_testing;

__END__
