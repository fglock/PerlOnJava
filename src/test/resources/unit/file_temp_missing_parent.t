use strict;
use warnings;

use File::Spec;
use File::Temp;
use Test::More tests => 2;

my $root = File::Temp::tempdir(CLEANUP => 1);
my $missing = File::Spec->catdir($root, 'does_not_exist');

my $error = eval { File::Temp->new(DIR => $missing); 1 } ? '' : $@;

like($error, qr/Parent directory .* does not exist/,
    'tempfile reports a missing parent directory');
ok(!-e $missing, 'failed tempfile creation does not create the parent');
