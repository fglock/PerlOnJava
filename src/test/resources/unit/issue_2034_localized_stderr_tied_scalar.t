use strict;
use warnings;
use Test::More;

{
    package IO::Scalar;
    no warnings 'once';

    # Minimal tied glob-reference handle matching IO::Scalar's construction
    # path. The regression concerns assignment of this blessed GLOBREFERENCE
    # into a localized standard-error glob, not the full CPAN module API.
    sub new {
        my ($class) = @_;
        my $buffer = '';
        my $self = bless \do { local *FH }, $class;
        *$self->{buffer} = \$buffer;
        tie *$self, $class, $self;
        return $self;
    }

    sub TIEHANDLE { $_[1] }

    sub PRINT {
        my $self = shift;
        my $buffer = *$self->{buffer};
        $$buffer .= join '', @_;
        return 1;
    }

    sub contents {
        my $buffer = *{$_[0]}->{buffer};
        return $$buffer;
    }
}

my $capture = IO::Scalar->new();
{
    local *STDERR = $capture;
    print STDERR "diagnostic\n";
}

is($capture->contents, "diagnostic\n",
    'localized STDERR writes reach a blessed tied scalar handle');

done_testing;
