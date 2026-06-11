use strict;
use warnings;
use Test::More;
use Symbol qw(gensym);

{
    package TieHandleReadlineArgs;

    our @seen;

    sub TIEHANDLE {
        bless { lines => [ 'first', 'second' ] }, shift;
    }

    sub READLINE {
        push @seen, [ scalar(@_), scalar(wantarray) ];
        goto &getlines if wantarray;
        goto &getline;
    }

    sub getline {
        my $self = shift;
        return shift @{ $self->{lines} };
    }

    sub getlines {
        my $self = shift;
        return @{ $self->{lines} };
    }
}

my $fh = gensym();
tie *$fh, 'TieHandleReadlineArgs';

is(<$fh>, 'first', 'scalar readline returns one record');
is_deeply($TieHandleReadlineArgs::seen[0], [ 1, '' ],
    'scalar tied READLINE receives only the tied object');

my @lines = <$fh>;
is_deeply(\@lines, [ 'second' ], 'list readline returns remaining records');
is_deeply($TieHandleReadlineArgs::seen[1], [ 1, 1 ],
    'list tied READLINE receives only the tied object');

done_testing();
