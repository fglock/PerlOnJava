use strict;
use warnings;
use Test::More tests => 2;
use File::Temp;

my $fh = File::Temp->new;
ok(UNIVERSAL::isa($fh, 'GLOB') || UNIVERSAL::isa($fh, 'FileHandle'),
   'File::Temp object exposes a legacy-compatible filehandle identity');
ok(defined(fileno($fh)), 'File::Temp object has an open file descriptor');
