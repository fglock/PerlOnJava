package CPAN::FindDependencies::MakeMaker;

use strict;
use warnings;
use Exporter qw(import);
use File::Temp qw(tempdir);
use PerlOnJava::Process qw(run_process);

our $VERSION = '1.0';
our @EXPORT_OK = qw(getreqs_from_mm);

sub getreqs_from_mm {
    my ($makefile_pl) = @_;
    my $tempdir = tempdir(CLEANUP => 1);

    open my $fh, '>', "$tempdir/Makefile.PL"
        or die "Can't write Makefile.PL in $tempdir\n";
    print {$fh} $makefile_pl;
    close $fh;

    my $result = run_process(
        argv => [ $^X, 'Makefile.PL' ],
        cwd => $tempdir,
        timeout => 10,
    );
    return "Makefile.PL didn't finish in a reasonable time\n"
        if $result->{timed_out};
    return $result->{error} if length $result->{error};

    open $fh, '<', "$tempdir/Makefile" or return "Unable to get Makefile";
    local $/;
    my $makefile = <$fh>;
    close $fh;
    return _parse_makefile($makefile);
}

sub _parse_makefile {
    my ($makefile) = @_;
    return "Unable to get Makefile" unless defined $makefile;
    my %required_version_for;
    my @prereq_lines = grep { /^\s*#.*PREREQ/ } split /\n/, $makefile;
    for my $line (@prereq_lines) {
        if ($line =~ /PREREQ_PM \s+ => \s+ \{ \s* (.*) \s* \} $/x) {
            no strict 'subs';
            %required_version_for = eval "( $1 )";
            return "Failed to eval $1: $@" if $@;
        } else {
            return "Unrecognized PREREQ line in Makefile.PL:\n$line";
        }
    }
    return \%required_version_for;
}

1;
