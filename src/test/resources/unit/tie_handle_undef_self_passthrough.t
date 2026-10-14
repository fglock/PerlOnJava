use strict;
use warnings;
use Test::More tests => 4;

{
    package UndefTiedHandle;
    our $print_calls = 0;

    sub TIEHANDLE { return undef }
    sub PRINT { $print_calls++; return 1 }
}

my $output = '';
open my $fh, '>', \$output or die "open scalar handle: $!";
tie *$fh, 'UndefTiedHandle';

print {$fh} "first\n";
print {$fh} "second\n";
my $closed = close $fh;

is($output, "first\nsecond\n", 'writes pass through to the original handle when TIEHANDLE returns undef');
is($UndefTiedHandle::print_calls, 0, 'the class PRINT method is not called without a returned tie object');
ok(!defined(tied *$fh), 'tied() reports the undefined TIEHANDLE return value');
ok($closed, 'close passes through to the original handle when TIEHANDLE returns undef');
