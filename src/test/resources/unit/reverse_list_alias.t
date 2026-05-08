use 5.32.0;
use strict;
use warnings;
use Test::More;

# List-context reverse must return the same SVs as the source array, only in
# reversed order, so `foreach my $x (reverse @a)` aliases into @a. Copying
# elements breaks in-place mutation (Text::Format __make_line / justify tests).

{
    my @a = qw(x y);
    for my $x (reverse @a) {
        $x .= 'Z';
    }
    is_deeply( \@a, [qw(xZ yZ)], 'foreach (reverse @lexical) mutates original scalars' );
}

{
    my @a = (1, 2);
    for my $x (reverse @a) {
        $x++;
    }
    is_deeply( \@a, [ 2, 3 ], 'foreach (reverse @lexical) numeric mutator updates slots' );
}

{
    my @words = split /(\s+)/, 'a b c';
    my $width = 7;
    my $spaces  = $width - length join '', @words;
    my $ws      = int( $spaces / int( @words / 2 ) );
    $spaces %= int( @words / 2 ) if $ws > 0;
    for my $gap ( reverse @words ) {
        next if $gap =~ /^\S/;
        substr( $gap, 0, 0 ) = ' ' x $ws;
        $spaces || next;
        substr( $gap, 0, 0 ) = ' ';
        --$spaces;
    }
    my $line = join '', @words;
    is( length($line), $width, 'justify-style substr prepend via reverse foreach reaches target width' );
    is( $line, 'a  b  c', 'justify-style gaps updated in original split list' );
}

done_testing();
