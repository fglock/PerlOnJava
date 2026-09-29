use strict;
use warnings;
use Test::More;

my $contents = "scalar-backed input\n";
open my $handle, '<', \$contents or die "open scalar-backed handle: $!";

is(ref($handle), 'GLOB', 'scalar-backed handle has GLOB reference type');
ok(UNIVERSAL::isa($handle, 'GLOB'),
    'UNIVERSAL::isa recognizes a scalar-backed handle as GLOB');
ok(!UNIVERSAL::isa($handle, 'IO::Handle'),
    'UNIVERSAL::isa does not replace the GLOB reference type with IO::Handle');

ok(UNIVERSAL::isa(\*STDIN, 'GLOB'),
    'UNIVERSAL::isa recognizes a glob reference with an IO slot as GLOB');

done_testing;
