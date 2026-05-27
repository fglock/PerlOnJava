use strict;
use warnings;
use Test::More tests => 5;
use File::Path qw(rmtree);
use File::Temp qw(tempdir);

{
    my $dir = tempdir(CLEANUP => 0);
    File::Temp::cleanup();

    ok(-d $dir, 'tempdir(CLEANUP => 0) is not registered for cleanup');
    ok(rmtree($dir), 'preserved temp directory manually cleaned up');
}

{
    package WarnifRegression;
    require Exporter;
    our @ISA = qw(Exporter);
    our @EXPORT = qw(warnif_probe);

    use warnings;

    sub warnif_probe {
        warnings::warnif(void => 'warnif probe');
    }
}

WarnifRegression->import;

{
    no warnings;
    use warnings 'void';

    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, $_[0] };

    warnif_probe();

    is(scalar @warnings, 1, 'warnings::warnif sees call-site warning bits');
    like($warnings[0], qr/\Qfile_temp_warnif_regression.t\E/, 'warnings::warnif reports call-site location');
}

{
    no warnings 'void';

    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, $_[0] };

    warnif_probe();

    is(scalar @warnings, 0, 'warnings::warnif honors disabled call-site category');
}
