use strict;
use Test::More tests => 3;

my $chars = '\Q';

{
    no warnings 'regexp';
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $re = qr/[$chars]/;
    is scalar(@warnings), 0, 'interpolated character-class escapes honor no warnings';
}

{
    use warnings 'regexp';
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $re = qr/[$chars]/;
    is scalar(@warnings), 1, 'interpolated character-class escapes honor use warnings';
}

{
    use warnings FATAL => 'regexp';
    my $error = eval { my $re = qr/[$chars]/; 1 } ? '' : $@;
    like $error, qr/Unrecognized escape \\Q/, 'fatal regexp warnings remain fatal';
}
