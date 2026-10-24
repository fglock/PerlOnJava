use strict;
use warnings;
use Test::More;

use FileHandle;

ok(defined &IO::Seekable::getpos,
    'IO::Seekable::getpos is available while FileHandle loads');
ok(defined &FileHandle::getpos, 'FileHandle installs the getpos compatibility alias');

my $fh = FileHandle->new;
ok($fh, 'construct a FileHandle');
ok($fh->open(__FILE__, '<'), 'open a seekable file');
my $position = $fh->getpos;
ok(defined $position, 'getpos returns the current position');
ok($fh->setpos($position), 'setpos restores the position');
$fh->close;

done_testing;
