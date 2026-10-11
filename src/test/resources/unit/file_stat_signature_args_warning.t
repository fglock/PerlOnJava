use strict;
use warnings;
use Test::More tests => 2;

my @warnings;
my $ok;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $ok = eval q{
        use File::stat;
        my $status = stat ".";
        die "stat failed" unless defined $status;
        1;
    };
}

ok($ok, 'File::stat stat wrapper runs successfully');
is(scalar @warnings, 0,
    'ordinary CORE::stat calls in signatured wrappers do not warn about @_');
