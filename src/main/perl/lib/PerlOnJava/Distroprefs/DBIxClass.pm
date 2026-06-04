package PerlOnJava::Distroprefs::DBIxClass;

use strict;
use warnings;
use Config ();

sub test_phase {
    my $exec = $ENV{JPERL_BIN} || $ENV{PERLONJAVA_EXECUTABLE} || 'jperl';

    my @tests = grep { -f $_ } qw(
        t/04_c3_mro.t
        t/05components.t
        t/100populate.t
        t/101populate_rs.t
        t/101source.t
        t/20setuperrors.t
        t/39load_namespaces_stress.t
        t/52leaks.t
        t/53lean_startup.t
        t/60core.t
        t/64db.t
        t/65multipk.t
    );
    die "PerlOnJava::Distroprefs::DBIxClass: no DBIC smoke tests found\n"
        unless @tests;

    my @lib = ('blib/lib', 'blib/arch');
    push @lib, $ENV{PERL5LIB}
        if defined $ENV{PERL5LIB} && length $ENV{PERL5LIB};
    local $ENV{PERL5LIB} = join($Config::Config{path_sep}, @lib);

    my @cmd = (
        $exec,
        '-MExtUtils::Command::MM',
        '-MTest::Harness',
        '-e',
        "undef *Test::Harness::Switches; test_harness(0, 'blib/lib', 'blib/arch')",
        @tests,
    );

    print "PerlOnJava::Distroprefs::DBIxClass: running focused smoke/leak/core subset\n";
    print "PerlOnJava::Distroprefs::DBIxClass: @cmd\n";

    my $rc = system(@cmd);
    die "PerlOnJava::Distroprefs::DBIxClass: failed to execute $exec: $!\n"
        if $rc == -1;
    die "PerlOnJava::Distroprefs::DBIxClass: died with signal " . ($rc & 127) . "\n"
        if $rc & 127;

    my $exit = $rc >> 8;
    die "PerlOnJava::Distroprefs::DBIxClass: exited $exit\n"
        if $exit != 0;

    print "PerlOnJava::Distroprefs::DBIxClass: focused subset ok\n";
    return 1;
}

1;
