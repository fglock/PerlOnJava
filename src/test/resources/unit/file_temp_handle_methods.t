use strict;
use warnings;
use Test::More tests => 8;
use File::Temp;
use File::Basename qw(basename);

my $file = File::Temp->new;

ok($file->can('write'), 'File::Temp object exposes IO::Handle write method');
ok($file->write('abcdef', 3), 'write accepts an explicit byte length');
ok($file->write('012345', 2, 2), 'write accepts a byte offset');
ok($file->seek(0, 0), 'temporary file can seek back to the start');

my $contents = '';
is($file->read($contents, 5), 5, 'read reports the number of bytes read');
is($contents, 'abc23', 'write length and offset match IO::Handle semantics');

is(length(basename(File::Temp->new->filename)), 10,
    'default temporary filename contains ten generated characters');
like(basename(File::Temp->new(SUFFIX => '.pl')->filename), qr/^.{10}\.pl$/,
    'suffix follows the ten-character default temporary filename');
