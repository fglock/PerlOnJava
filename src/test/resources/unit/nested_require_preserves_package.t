use strict;
use warnings;
use Test::More;
use File::Path qw(make_path);
use File::Temp qw(tempdir);

my $root = tempdir(CLEANUP => 1);
make_path("$root/Issue2078Probe/Vectorize");

open my $module, '>', "$root/Issue2078Probe/Vectorize.pm" or die $!;
print {$module} <<'MODULE';
package Issue2078Probe::Vectorize;
require 'Issue2078Probe/Vectorize/Datatype.pl';
sub initial { is_array_ref([1]) ? 'ok' : 'bad' }
1;
MODULE
close $module or die $!;

open my $helper, '>', "$root/Issue2078Probe/Vectorize/Datatype.pl" or die $!;
print {$helper} <<'HELPER';
sub is_array_ref { ref($_[0]) eq 'ARRAY' }
1;
HELPER
close $helper or die $!;

local @INC = ($root, @INC);
require Issue2078Probe::Vectorize;
is(Issue2078Probe::Vectorize::initial(), 'ok',
    'nested require without a package declaration keeps its caller package');

done_testing();
