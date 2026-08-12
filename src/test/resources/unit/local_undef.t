use strict;
use warnings;
use Test::More tests => 6;

$/ = "record";
is($/, 'record', 'input record separator starts defined');

{
    local undef $/;
    ok(!defined($/), 'local undef clears the target');
}

ok(!defined($/), 'local undef does not restore the target');

sub slurp_with_legacy_local_undef {
    my ($fh) = @_;
    local undef $/;
    return <$fh>;
}

my $input = "first\nsecond\n";
open my $fh, '<', \$input or die $!;
is(slurp_with_legacy_local_undef($fh), $input,
    'legacy local undef idiom slurps a handle');

my $callback = sub {
    local undef $/;
    return defined($/) ? 'defined' : 'undef';
};

is($callback->(), 'undef', 'local undef compiles inside a closure');
ok(!defined($/), 'closure leaves the target undefined');
