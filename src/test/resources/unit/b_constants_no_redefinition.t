use strict;
use warnings;
use Test::More tests => 2;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    require B;
    B->import(qw(CVf_CONST CVf_ANON SVf_IOK SVp_POK));
}

is scalar(@warnings), 0, 'loading B does not redefine its constants';
is_deeply [B::CVf_CONST(), B::CVf_ANON(), B::SVf_IOK(), B::SVp_POK()],
          [0x0004, 0x0080, 0x00000100, 0x00004000],
          'B constants retain their documented values';
