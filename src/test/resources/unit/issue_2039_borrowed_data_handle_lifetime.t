use strict;
use warnings;
use Test::More;
use FileHandle;

sub borrow_and_destroy {
    my $fh = *main::DATA{IO};
    bless $fh, 'FileHandle';
}

borrow_and_destroy();
is(scalar(<DATA>), "still readable\n",
    'destroying a FileHandle wrapper leaves the package DATA stream open');
done_testing;

__DATA__
still readable
