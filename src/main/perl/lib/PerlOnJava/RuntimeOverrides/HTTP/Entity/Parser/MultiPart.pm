package HTTP::Entity::Parser::MultiPart;

use strict;
use warnings;

my $original_finalize = \&finalize;
{
    no warnings 'redefine';
    *finalize = sub {
        my ($self, @args) = @_;
        my $wantarray = wantarray;
        my (@result, $result);
        if (!defined $wantarray) {
            $original_finalize->($self, @args);
        } elsif ($wantarray) {
            @result = $original_finalize->($self, @args);
        } else {
            $result = $original_finalize->($self, @args);
        }

        # The request environment owns the temp directory through
        # http.entity.parser.multipart.tempdir. Drop the parser's duplicate
        # owner once parsing finishes so a retained parser object cannot keep
        # upload files alive after the request environment is released.
        delete $self->{tempdir};

        return if !defined $wantarray;
        return $wantarray ? @result : $result;
    };
}

1;
