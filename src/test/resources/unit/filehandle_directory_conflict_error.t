use strict;
use warnings;
use Test::More;

opendir FOO, '.' or die "opendir FOO: $!";
eval { open FOO, '<', __FILE__; 1 };
like($@, qr/^Cannot open FOO as a filehandle: it is already open as a dirhandle/, 
    'open rejects an active directory handle');
closedir FOO;

open FOO, '<', __FILE__ or die "open FOO: $!";
eval { opendir FOO, '.'; 1 };
like($@, qr/^Cannot open FOO as a dirhandle: it is already open as a filehandle/,
    'opendir rejects an active file handle');
close FOO;

done_testing;
