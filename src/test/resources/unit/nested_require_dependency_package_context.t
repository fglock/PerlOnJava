use strict;
use warnings;
use Test::More;
use File::Path qw(make_path);
use File::Temp qw(tempdir);

my $root = tempdir(CLEANUP => 1);
make_path("$root/List/Vectorize/lib", "$root/Statistics");

open my $helper, '>', "$root/List/Vectorize/lib/Datatype.pl" or die $!;
print {$helper} <<'HELPER';
sub is_array_ref { ref($_[0]) eq 'ARRAY' }
1;
HELPER
close $helper or die $!;

open my $vectorize, '>', "$root/List/Vectorize.pm" or die $!;
print {$vectorize} <<'VECTORIZE';
package List::Vectorize;
require $ENV{ISSUE2078_HELPER_PATH};
1;
VECTORIZE
close $vectorize or die $!;

open my $statistics, '>', "$root/Statistics/Multtest.pm" or die $!;
print {$statistics} <<'STATISTICS';
package Statistics::Multtest;
use List::Vectorize;
sub initial { List::Vectorize::is_array_ref([1]) ? 'ok' : 'bad' }
1;
STATISTICS
close $statistics or die $!;

{
    local @INC = ($root, @INC);
    local $ENV{ISSUE2078_HELPER_PATH} = "$root/List/Vectorize/lib/Datatype.pl";
    require Statistics::Multtest;
}

ok(defined(&List::Vectorize::is_array_ref),
    'nested helper require defines CODE in the package that loaded its module');
is(Statistics::Multtest::initial(), 'ok',
    'a dependency loaded through use can call its nested helper');

done_testing();
