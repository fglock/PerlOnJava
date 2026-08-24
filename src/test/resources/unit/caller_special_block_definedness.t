use strict;
use warnings;
use Test::More;

our (@phase_results, @phase_warnings);

sub inspect_special_block_caller {
    my ($phase) = @_;
    my @frame = caller(1);

    my @enabled;
    {
        local $SIG{__WARN__} = sub { push @enabled, @_ };
        my $anonymous = $frame[3] =~ /::__ANON__$/;
        my $qualified = $frame[3] =~ /^ (.+) :: ([^:]+) $/x;
    }

    my @suppressed;
    {
        no warnings 'uninitialized';
        local $SIG{__WARN__} = sub { push @suppressed, @_ };
        my $anonymous = $frame[3] =~ /::__ANON__$/;
        my $qualified = $frame[3] =~ /^ (.+) :: ([^:]+) $/x;
    }

    push @phase_results, [$phase, $frame[3]];
    push @phase_warnings, [$phase, scalar @enabled, scalar @suppressed];
}

BEGIN { inspect_special_block_caller('BEGIN') }
CHECK { inspect_special_block_caller('CHECK') }
INIT  { inspect_special_block_caller('INIT') }

for my $result (@phase_results) {
    my ($phase, $subname) = @$result;
    ok(defined $subname, "$phase caller subroutine name is defined");
    like($subname, qr/\Amain::/, "$phase caller subroutine name is qualified");
}

for my $warnings (@phase_warnings) {
    my ($phase, $enabled, $suppressed) = @$warnings;
    is($enabled, 0, "$phase caller field produces no warnings at either Carp match site");
    is($suppressed, 0, "$phase caller field remains quiet with uninitialized warnings suppressed");
}

done_testing;
