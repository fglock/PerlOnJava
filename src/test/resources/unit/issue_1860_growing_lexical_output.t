use strict;
use warnings;
use Test::More tests => 4;

my $width = 4;
my $depth = 5;
my @output;

sub tumble {
    my ($level, $path, $values, $payload) = @_;
    if ($level == $depth) {
        push @output, [ $path, $values, $payload ];
        return;
    }

    for my $variant (1 .. $width) {
        tumble(
            $level + 1,
            [ @$path, $variant ],
            [ @$values, $variant ],
            { %$payload },
        );
    }
}

tumble(0, [], [], { payload => 'kept' });

is(scalar @output, $width ** $depth,
    'recursive callbacks append every result to the growing lexical output');
is_deeply($output[0][0], [ (1) x $depth ],
    'the first retained path survives recursive scope cleanup');
is_deeply($output[-1][0], [ (4) x $depth ],
    'the last retained path survives recursive scope cleanup');
is($output[-1][2]{payload}, 'kept',
    'the final callback retains its payload');
