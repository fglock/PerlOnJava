use strict;
use warnings;

use File::Spec;
use IO::Handle;
use Test::More tests => 2;

my $devnull = File::Spec->devnull;
open my $source, '<', $devnull or die "open $devnull: $!";

my @warnings;
my $dest = IO::Handle->new;
my $opened;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $opened = $dest->fdopen($source, 'r');
}

ok($opened, 'fdopen opens a newly constructed IO::Handle');
is_deeply(\@warnings, [], 'fdopen on a new IO::Handle does not warn');
