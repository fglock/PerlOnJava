use strict;
use warnings;
use Test::More;
use IO::File;

{
    package Issue1253::FileWithPath;
    our @ISA = ('IO::File');
    sub path { 'example/path' }
}

my $file = IO::File->new_tmpfile or die "could not create temporary file: $!";
bless $file, 'Issue1253::FileWithPath';

ok($file->can('path'), 'UNIVERSAL::can finds a subclass method on an IO::File handle');
is($file->path, 'example/path', 'the discovered method dispatches');

done_testing;
