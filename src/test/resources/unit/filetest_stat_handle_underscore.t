use strict;
use warnings;
use Test::More;

open my $handle, '<', __FILE__ or die "open __FILE__: $!";
my @stat = stat($handle);
is(scalar @stat, 13, 'stat on a filehandle succeeds');
ok(-e _, 'underscore file test sees filehandle stat existence');
ok(-f _, 'underscore file test sees a regular file from stat');
ok(-r _, 'underscore file test sees filehandle read permission');

close $handle;
done_testing;
