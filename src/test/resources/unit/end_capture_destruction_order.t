use strict;
use warnings;

our @destroyed;

{
    package EndCaptureOrderGuard;

    sub new {
        my $self = bless {}, $_[0];
        eval '$self';
        return $self;
    }

    sub DESTROY { push @main::destroyed, ref $_[0] }
}

my $instance = EndCaptureOrderGuard->new;

END {
    print "1..1\n";
    my $destroyed_before_end = @destroyed == 1
        && $destroyed[0] eq 'EndCaptureOrderGuard';
    print($destroyed_before_end ? "ok 1" : "not ok 1",
        " - eval capture does not delay lexical destruction past END\n");
}
